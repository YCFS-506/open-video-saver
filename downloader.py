"""Save public videos and image posts from supported platforms.

Browser adapters use dedicated profiles and never read the user's normal browser
profile. Edge, Playwright, and FFmpeg are required for browser based adapters.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import tempfile
from pathlib import Path
from typing import Callable
from urllib.parse import parse_qs, urlparse

from playwright.sync_api import BrowserContext, sync_playwright
from platforms import DownloadError, detect_platform, extract_url


Progress = Callable[[str, int, int], None]
VIDEO_ID = re.compile(r"/(?:share/)?video/(\d{17,19})")
NOTE_ID = re.compile(r"/(?:share/)?(?:note|slides)/(\d{17,19})")
CONTENT_RANGE = re.compile(r"bytes\s+(\d+)-(\d+)/(\d+)", re.IGNORECASE)
CHUNK_SIZE = 4 * 1024 * 1024


def resolve_work(context: BrowserContext, url: str) -> tuple[str, str]:
    for kind, pattern in (("video", VIDEO_ID), ("images", NOTE_ID)):
        match = pattern.search(url)
        if match:
            return kind, match.group(1)
    response = context.request.get(url, max_redirects=0, timeout=15000)
    targets = [response.headers.get("location", ""), response.text()[:5000]]
    for target in targets:
        for kind, pattern in (("video", VIDEO_ID), ("images", NOTE_ID)):
            match = pattern.search(target)
            if match:
                # Some older image posts use /share/video/ in the short-link
                # redirect, while schema_type=37 identifies the image post.
                schema = parse_qs(urlparse(target).query).get("schema_type", [])
                return ("images" if kind == "video" and "37" in schema else kind), match.group(1)
    raise DownloadError(f"短链已访问，但未能识别作品 ID（HTTP {response.status}）")


def resolve_video_id(context: BrowserContext, url: str) -> str:
    kind, work_id = resolve_work(context, url)
    if kind != "video":
        raise DownloadError("这是图文作品，请选择图片和文案下载")
    return work_id


def read_douyin_gallery(page, post_id: str, share_url: str) -> dict:
    page.goto(f"https://www.douyin.com/note/{post_id}", wait_until="domcontentloaded", timeout=30000)
    match = NOTE_ID.search(page.url)
    if not match or match.group(1) != post_id:
        raise DownloadError("这条作品没有打开为抖音图文页")
    selector = 'img[src*="tplv-dy-aweme-images"]'
    try:
        page.wait_for_selector(selector, timeout=15000)
    except Exception as error:
        raise DownloadError("抖音图文页没有显示图片；请确认作品公开且网页可以查看") from error
    previous = -1
    stable = 0
    for _ in range(20):
        count = page.locator(selector).count()
        stable = stable + 1 if count == previous else 0
        if stable >= 3:
            break
        previous = count
        page.wait_for_timeout(500)
    raw_urls = page.locator(selector).evaluate_all("xs => xs.map(x => x.currentSrc || x.src)")
    images = []
    seen = set()
    for address in raw_urls:
        parsed = urlparse(address)
        host = (parsed.hostname or "").lower()
        if parsed.scheme != "https" or not host.endswith(".douyinpic.com") or address in seen:
            continue
        seen.add(address)
        images.append([address])
    if not images:
        raise DownloadError("抖音图文页没有返回可保存的图片")
    details = page.evaluate("""() => {
        const stamp = Array.from(document.querySelectorAll('span'))
            .find(x => x.textContent?.startsWith('发布时间：'));
        const root = stamp?.parentElement?.parentElement;
        const button = root?.querySelector('button');
        if (button?.textContent?.includes('展开')) button.click();
        return {
            caption: root?.firstElementChild?.firstElementChild?.innerText || '',
            published: stamp?.textContent?.replace('发布时间：', '').trim() || '',
            meta: document.querySelector('meta[name="description"]')?.content || '',
        };
    }""")
    caption = (details.get("caption") or "").strip()
    meta = details.get("meta") or ""
    if not caption and " - " in meta:
        caption = meta.rsplit(" - ", 1)[0].strip()
    author_match = re.search(r" - (.+?)于\d{8}发布在抖音", meta)
    return {
        "id": f"douyin:images:{post_id}", "platform": "douyin", "kind": "images",
        "width": 0, "height": 0, "fps": 0, "codec": "IMAGE", "bytes": 0,
        "image_count": len(images), "images": images, "caption": caption,
        "author": author_match.group(1) if author_match else "",
        "published": details.get("published") or "", "video_id": post_id,
        "source_url": share_url,
    }


def available_qualities(video: dict) -> list[dict]:
    options = []
    for variant in video.get("bit_rate", []):
        address = variant.get("play_addr") or {}
        width = int(address.get("width") or 0)
        height = int(address.get("height") or 0)
        if variant.get("format") == "mp4" and width and height and address.get("url_list"):
            fps = float(variant.get("FPS") or 0)
            bitrate = int(variant.get("bit_rate") or 0)
            codec = "H.265" if variant.get("is_h265") else "H.264"
            gear = variant.get("gear_name") or ""
            options.append({
                "id": f"{width}x{height}:{fps:g}:{codec}:{bitrate}:{gear}",
                "width": width, "height": height, "fps": fps, "codec": codec,
                "bitrate": bitrate, "bytes": int(address.get("data_size") or 0),
                "urls": address["url_list"],
            })
    options.sort(key=lambda item: (
        item["width"] * item["height"], item["fps"],
        item["codec"] == "H.264", item["bitrate"],
    ), reverse=True)
    return options


def probe_quality_stream(context: BrowserContext, variants: list[dict], referer: str) -> dict | None:
    for variant in variants:
        for url in variant["urls"]:
            parsed_url = urlparse(url)
            if parsed_url.scheme != "https" or not (parsed_url.hostname or "").endswith(".douyinvod.com"):
                continue
            try:
                response = context.request.get(
                    url, headers={"Range": "bytes=0-0", "Referer": referer}, timeout=8000,
                )
                content_range = CONTENT_RANGE.fullmatch(response.headers.get("content-range", ""))
                if response.status == 206 and content_range:
                    return {"url": url, "total": int(content_range.group(3)),
                            "quality": f"{variant['width']}×{variant['height']} · {variant['fps']:g} 帧/秒"}
            except Exception:
                continue
    return None


def select_quality_stream(context: BrowserContext, video: dict, quality: str,
                          referer: str) -> dict | None:
    variants = available_qualities(video)
    if quality == "1080":
        at_most_1080 = [item for item in variants if
                        min(item["width"], item["height"]) <= 1080 and
                        max(item["width"], item["height"]) <= 1920]
        if at_most_1080:
            variants = at_most_1080
    elif quality != "best":
        variants = [item for item in variants if item["id"] == quality]
    return probe_quality_stream(context, variants, referer)


def inspect_page(page, video_id: str, need_media: bool = True) -> tuple[dict | None, list[dict]]:
    media: list[dict] = []
    details: list[dict] = []
    seen: set[tuple[str, int]] = set()

    def on_response(response) -> None:
        if f"aweme_id={video_id}" in response.url and "/aweme/v1/web/aweme/detail/" in response.url and not details:
            try:
                video = (response.json().get("aweme_detail") or {}).get("video")
                if video:
                    details.append(video)
            except Exception:
                pass
            return
        host = urlparse(response.url).hostname or ""
        if not host.endswith(".douyinvod.com") or response.status not in {200, 206}:
            return
        if "mp4" not in response.headers.get("content-type", ""):
            return
        range_header = response.headers.get("content-range", "")
        parsed = CONTENT_RANGE.fullmatch(range_header)
        total = int(parsed.group(3)) if parsed else int(response.headers.get("content-length", "0") or "0")
        if total < 1_000_000:
            return
        key = (urlparse(response.url).path, total)
        if key not in seen:
            seen.add(key)
            media.append({"url": response.url, "total": total})

    page.on("response", on_response)
    page.goto(f"https://www.douyin.com/video/{video_id}", wait_until="domcontentloaded", timeout=30000)
    if NOTE_ID.search(page.url):
        return None, []
    if need_media:
        page.wait_for_timeout(1000)
        if page.locator("video").count():
            try:
                page.locator("video").first.evaluate("video => video.play().catch(() => {})")
            except Exception:
                pass
    for _ in range(20 if need_media else 60):
        if details and (not need_media or len(media) >= 2):
            break
        page.wait_for_timeout(200 if not need_media else 500)
    return (details[0] if details else None), media[:2]


def capture_streams(page, video_id: str, quality: str) -> list[dict]:
    video, media = inspect_page(page, video_id)
    referer = f"https://www.douyin.com/video/{video_id}"
    if video:
        selected = select_quality_stream(page.context, video, quality, referer)
        if selected:
            audio = next((stream for stream in media if "media-audio" in urlparse(stream["url"]).path), None)
            return [selected] + ([audio] if audio else [])
    if quality not in {"1080", "best"}:
        raise DownloadError("所选画质暂不可用，请重新读取画质列表")
    if not media:
        raise DownloadError("页面已打开，但未发现可下载的公开 MP4 播放流")
    return media[:2]


def list_qualities(text: str) -> list[dict]:
    platform = detect_platform(text)
    if platform == "bilibili":
        from bilibili import list_qualities as list_bilibili_qualities
        return list_bilibili_qualities(text)
    if platform == "xiaohongshu":
        from xiaohongshu import list_qualities as list_xiaohongshu_qualities
        return list_xiaohongshu_qualities(text)
    if platform == "kuaishou":
        from kuaishou import list_qualities as list_kuaishou_qualities
        return list_kuaishou_qualities(text)
    url = extract_url(text)
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(channel="msedge", headless=True)
        try:
            context = browser.new_context(locale="zh-CN")
            kind, video_id = resolve_work(context, url)
            page = context.new_page()
            if kind == "images":
                return [read_douyin_gallery(page, video_id, url)]
            video, _ = inspect_page(page, video_id, need_media=False)
            if NOTE_ID.search(page.url):
                return [read_douyin_gallery(page, video_id, url)]
            if not video:
                try:
                    return [read_douyin_gallery(page, video_id, url)]
                except DownloadError:
                    raise DownloadError("页面未返回画质列表，请稍后重试") from None
            options = available_qualities(video)
            if not options:
                try:
                    return [read_douyin_gallery(page, video_id, url)]
                except DownloadError:
                    raise DownloadError("这个视频未提供可选的 MP4 画质") from None
            for option in options:
                option["video_id"] = video_id
                option["source_url"] = url
            return options
        finally:
            browser.close()


def download_stream(context: BrowserContext, url: str, total: int, destination: Path,
                    referer: str, phase: str, progress: Progress) -> None:
    written = 0
    with destination.open("wb") as output:
        while written < total:
            end = min(written + CHUNK_SIZE, total) - 1
            last_error = None
            for _ in range(3):
                try:
                    response = context.request.get(
                        url,
                        headers={"Range": f"bytes={written}-{end}", "Referer": referer},
                        timeout=60000,
                    )
                    content_range = CONTENT_RANGE.fullmatch(response.headers.get("content-range", ""))
                    body = response.body()
                    if response.status != 206 or not content_range:
                        raise DownloadError(f"媒体请求返回 HTTP {response.status}")
                    start, actual_end, actual_total = map(int, content_range.groups())
                    if (start, actual_end, actual_total) != (written, end, total) or len(body) != end - written + 1:
                        raise DownloadError("媒体分段长度与服务器声明不一致")
                    output.write(body)
                    written += len(body)
                    progress(phase, written, total)
                    break
                except Exception as error:
                    last_error = error
            else:
                raise DownloadError(f"{phase}下载失败：{last_error}")


def inspect_media(path: Path) -> dict:
    ffprobe = shutil.which("ffprobe")
    if not ffprobe:
        raise DownloadError("未找到 FFprobe；请安装 FFmpeg 并加入 PATH")
    result = subprocess.run(
        [ffprobe, "-v", "error", "-show_entries", "stream=codec_type,codec_name:format=duration", "-of", "json", str(path)],
        capture_output=True, text=True, encoding="utf-8", errors="replace", check=False,
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
    )
    if result.returncode:
        raise DownloadError(f"下载文件无法被 FFprobe 识别：{result.stderr[:200]}")
    return json.loads(result.stdout)


def unique_path(folder: Path, video_id: str) -> Path:
    candidate = folder / f"{video_id}.mp4"
    index = 2
    while candidate.exists():
        candidate = folder / f"{video_id}_{index}.mp4"
        index += 1
    return candidate


def save_video(text: str, output_folder: Path, progress: Progress = lambda *_: None,
               quality: str = "1080", cached_option: dict | None = None) -> Path:
    platform = detect_platform(text)
    if platform == "bilibili":
        from bilibili import save_video as save_bilibili_video
        return save_bilibili_video(text, output_folder, progress, quality, cached_option)
    if platform == "xiaohongshu":
        from xiaohongshu import save_video as save_xiaohongshu_video
        return save_xiaohongshu_video(text, output_folder, progress, quality, cached_option)
    if platform == "kuaishou":
        from kuaishou import save_video as save_kuaishou_video
        return save_kuaishou_video(text, output_folder, progress, quality, cached_option)
    url = extract_url(text)
    output_folder.mkdir(parents=True, exist_ok=True)
    progress("启动浏览器", 0, 1)
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(channel="msedge", headless=True)
        try:
            context = browser.new_context(locale="zh-CN")
            cached_id = (cached_option or {}).get("video_id", "")
            if (cached_option and cached_option.get("source_url") == url and
                    re.fullmatch(r"\d{17,19}", cached_id)):
                kind, video_id = cached_option.get("kind", "video"), cached_id
            else:
                kind, video_id = resolve_work(context, url)
            def save_image_work() -> Path:
                from image_posts import save_gallery
                option = (cached_option if cached_option and cached_option.get("kind") == "images"
                          and cached_option.get("source_url") == url else
                          read_douyin_gallery(context.new_page(), video_id, url))

                def fetch_image(address: str) -> tuple[bytes, str]:
                    response = context.request.get(
                        address, headers={"Referer": f"https://www.douyin.com/note/{video_id}"},
                        timeout=30000,
                    )
                    if response.status != 200:
                        raise DownloadError(f"图片请求返回 HTTP {response.status}")
                    return response.body(), response.headers.get("content-type", "")

                return save_gallery(output_folder, "抖音", video_id, option["images"],
                                    option["caption"], url, fetch_image, progress,
                                    option.get("author", ""), option.get("published", ""),
                                    option.get("selected_indices"), option.get("image_cache"))

            if kind == "images":
                return save_image_work()
            media = []
            from_cache = False
            if (cached_option and cached_option.get("id") == quality and
                    cached_option.get("source_url") == url and
                    re.fullmatch(r"\d{17,19}", cached_id)):
                video_id = cached_id
                referer = f"https://www.douyin.com/video/{video_id}"
                progress("校验已选画质", 0, 1)
                selected = probe_quality_stream(context, [cached_option], referer)
                if selected:
                    media = [selected]
                    from_cache = True
            if not media:
                progress("读取视频信息", 0, 1)
                page = context.new_page()
                try:
                    media = capture_streams(page, video_id, quality)
                except DownloadError as error:
                    try:
                        return save_image_work()
                    except DownloadError:
                        raise error from None
            referer = f"https://www.douyin.com/video/{video_id}"
            progress(f"找到 {media[0]['quality']} 播放流" if "quality" in media[0] else "找到播放流", 1, 1)
            with tempfile.TemporaryDirectory(prefix="douyin_", dir=output_folder) as temp_name:
                temp = Path(temp_name)
                downloaded = []
                for index, stream in enumerate(media, 1):
                    part = temp / f"stream_{index}.mp4"
                    download_stream(context, stream["url"], stream["total"], part,
                                    referer, f"下载流 {index}/{len(media)}", progress)
                    info = inspect_media(part)
                    downloaded.append((part, info))
                    codecs = {item.get("codec_type") for item in info.get("streams", [])}
                    if {"video", "audio"}.issubset(codecs):
                        break
                    if from_cache and index == 1 and "video" in codecs and "audio" not in codecs:
                        page = context.new_page()
                        playback = capture_streams(page, video_id, quality)
                        audio = next((item for item in playback if "media-audio" in urlparse(item["url"]).path), None)
                        if audio:
                            media.append(audio)

                video_parts = [path for path, info in downloaded if any(s.get("codec_type") == "video" for s in info.get("streams", []))]
                audio_parts = [path for path, info in downloaded if any(s.get("codec_type") == "audio" for s in info.get("streams", []))]
                if not video_parts:
                    raise DownloadError("下载完成，但没有找到可播放的视频轨")
                video_part = video_parts[0]
                destination = unique_path(output_folder, video_id)
                if video_part in audio_parts:
                    shutil.move(str(video_part), str(destination))
                elif audio_parts:
                    ffmpeg = shutil.which("ffmpeg")
                    if not ffmpeg:
                        raise DownloadError("未找到 FFmpeg；无法合并音视频")
                    result = subprocess.run(
                        [ffmpeg, "-hide_banner", "-loglevel", "error", "-i", str(video_part),
                         "-i", str(audio_parts[0]), "-map", "0:v:0", "-map", "1:a:0", "-c", "copy",
                         "-movflags", "+faststart", str(destination)],
                        capture_output=True, text=True, encoding="utf-8", errors="replace", check=False,
                        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
                    )
                    if result.returncode:
                        destination.unlink(missing_ok=True)
                        raise DownloadError(f"音视频合并失败：{result.stderr[:300]}")
                else:
                    shutil.move(str(video_part), str(destination))
                final_info = inspect_media(destination)
                if not any(s.get("codec_type") == "video" for s in final_info.get("streams", [])):
                    destination.unlink(missing_ok=True)
                    raise DownloadError("输出文件缺少视频轨")
                progress("完成", 1, 1)
                return destination
        finally:
            browser.close()


def main() -> None:
    parser = argparse.ArgumentParser(description="保存抖音、哔哩哔哩、小红书或快手的公开作品")
    parser.add_argument("share_text", help="作品分享链接或包含链接的分享文案")
    parser.add_argument("--output", type=Path, default=Path.cwd(), help="保存目录")
    parser.add_argument("--quality", choices=["1080", "best"], default="1080",
                        help="1080 优先选择不超过 1080p 的清晰流；best 选择最高分辨率")
    args = parser.parse_args()

    def show_progress(phase: str, done: int, total: int) -> None:
        if total > 1:
            print(f"{phase}: {done * 100 // total}%", flush=True)
        else:
            print(phase, flush=True)

    try:
        saved = save_video(args.share_text, args.output, show_progress, args.quality)
        print(f"已保存：{saved}")
    except DownloadError as error:
        parser.exit(1, f"失败：{error}\n")


if __name__ == "__main__":
    main()
