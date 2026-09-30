"""Save publicly accessible Bilibili playback streams without account cookies."""

from __future__ import annotations

import json
import re
import shutil
import subprocess
import tempfile
from fractions import Fraction
from pathlib import Path
from typing import Callable
from urllib.parse import parse_qs, urlparse

import requests
from playwright.sync_api import sync_playwright

from platforms import DownloadError, extract_url
from image_posts import save_gallery


Progress = Callable[[str, int, int], None]
BVID = re.compile(r"/video/(BV[0-9A-Za-z]{10})(?:/|$)", re.IGNORECASE)
OPUS_ID = re.compile(r"/(?:opus|dynamic)/(\d{15,20})(?:/|$)")
CONTENT_RANGE = re.compile(r"bytes\s+(\d+)-(\d+)/(\d+)", re.IGNORECASE)
CHUNK_SIZE = 4 * 1024 * 1024
QUALITY_IDS = (127, 126, 125, 120, 116, 80, 64, 32, 16)
QUALITY_HEIGHT = {127: 4320, 126: 2160, 125: 2160, 120: 2160,
                  116: 1080, 80: 1080, 64: 720, 32: 480, 16: 360}


def new_session(referer: str) -> requests.Session:
    session = requests.Session()
    session.headers.update({
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36",
        "Referer": referer,
    })
    return session


def opus_identity(share_url: str) -> str | None:
    parsed = urlparse(share_url)
    match = OPUS_ID.search(parsed.path)
    if match:
        return match.group(1)
    if parsed.hostname == "t.bilibili.com":
        match = re.fullmatch(r"/(\d{15,20})/?", parsed.path)
        if match:
            return match.group(1)
    if (parsed.hostname or "") not in {"b23.tv", "bili2233.cn"}:
        return None
    try:
        with new_session(share_url) as session:
            with session.get(share_url, stream=True, timeout=15) as response:
                return (match.group(1) if (match := OPUS_ID.search(urlparse(response.url).path)) else None)
    except requests.RequestException:
        return None


def collect_opus(ident: str, share_url: str) -> dict:
    page_url = f"https://www.bilibili.com/opus/{ident}"
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(channel="msedge", headless=True)
        try:
            page = browser.new_page(locale="zh-CN")
            response = page.goto(page_url, wait_until="domcontentloaded", timeout=30000)
            if not response or response.status != 200:
                raise DownloadError("哔哩哔哩图文页面未正常打开")
            detail = page.evaluate("window.__INITIAL_STATE__?.detail || null")
        finally:
            browser.close()
    if not isinstance(detail, dict) or str(detail.get("id_str")) != ident:
        raise DownloadError("图文页面未返回作品数据，可能需要登录或作品已不可见")
    images, seen, paragraphs = [], set(), []
    title = author = published = ""

    def add_picture(item: dict) -> None:
        address = (item or {}).get("url") or ""
        if address.startswith("http://"):
            address = "https://" + address[7:]
        parsed = urlparse(address)
        if (parsed.scheme != "https" or not re.fullmatch(r"i\d+\.hdslb\.com", parsed.hostname or "")
                or not parsed.path.startswith("/bfs/") or address in seen):
            return
        seen.add(address)
        images.append([address])

    for module in detail.get("modules") or []:
        if module.get("module_type") == "MODULE_TYPE_TOP":
            top = module.get("module_top") or {}
            display = top.get("display") or {}
            for picture in (display.get("album") or {}).get("pics") or []:
                add_picture(picture)
        elif module.get("module_type") == "MODULE_TYPE_TITLE":
            title = ((module.get("module_title") or {}).get("text") or "").strip()
        elif module.get("module_type") == "MODULE_TYPE_AUTHOR":
            data = module.get("module_author") or {}
            author, published = data.get("name") or "", data.get("pub_time") or ""
        elif module.get("module_type") == "MODULE_TYPE_CONTENT":
            for paragraph in ((module.get("module_content") or {}).get("paragraphs") or []):
                for picture in ((paragraph.get("pic") or {}).get("pics") or []):
                    add_picture(picture)
                for node in ((paragraph.get("text") or {}).get("nodes") or []):
                    word = (node.get("word") or {}).get("words") or ""
                    if word:
                        paragraphs.append(word)
    if not images:
        raise DownloadError("这条哔哩哔哩作品没有公开图片")
    return {"id": f"bili:images:{ident}", "platform": "bilibili", "kind": "images",
            "width": 0, "height": 0, "fps": 0, "codec": "IMAGE", "bytes": 0,
            "image_count": len(images), "images": images,
            "caption": "\n".join([title] + paragraphs).strip(),
            "author": author, "published": published, "opus_id": ident,
            "source_url": share_url}


def video_identity(session: requests.Session, share_url: str) -> tuple[str, int]:
    match = BVID.search(urlparse(share_url).path)
    final_url = share_url
    if not match:
        try:
            with session.get(share_url, stream=True, timeout=15) as response:
                final_url = response.url
        except requests.RequestException as error:
            raise DownloadError(f"短链访问失败：{error}") from error
        if (urlparse(final_url).hostname or "") not in {"www.bilibili.com", "bilibili.com", "m.bilibili.com"}:
            raise DownloadError("短链未跳转到哔哩哔哩视频页")
        match = BVID.search(urlparse(final_url).path)
    if not match:
        raise DownloadError("未能从链接中识别 BV 号")
    try:
        page = int((parse_qs(urlparse(share_url).query).get("p") or
                    parse_qs(urlparse(final_url).query).get("p") or ["1"])[0])
    except ValueError:
        page = 1
    return match.group(1), max(1, page)


def api_data(session: requests.Session, path: str, params: dict) -> dict:
    try:
        response = session.get("https://api.bilibili.com" + path, params=params, timeout=15)
        response.raise_for_status()
        result = response.json()
    except (requests.RequestException, ValueError) as error:
        raise DownloadError(f"哔哩哔哩接口请求失败：{error}") from error
    if result.get("code") != 0 or not isinstance(result.get("data"), dict):
        raise DownloadError(f"哔哩哔哩接口未返回视频数据：{result.get('message', result.get('code'))}")
    return result["data"]


def valid_media_urls(urls: list[str]) -> list[str]:
    result = []
    for url in urls:
        parsed = urlparse(url)
        if parsed.scheme == "https" and parsed.hostname and parsed.hostname not in {"localhost", "127.0.0.1"}:
            result.append(url)
    return result


def address_urls(item: dict) -> list[str]:
    return valid_media_urls([item.get("baseUrl") or item.get("base_url") or item.get("url") or ""] +
                            (item.get("backupUrl") or item.get("backup_url") or []))


def probe_mp4_info(session: requests.Session, urls: list[str], quality_id: int) -> tuple[int, int, float, str]:
    for url in urls:
        try:
            with session.get(url, headers={"Range": "bytes=0-262143"}, stream=True, timeout=12) as response:
                if response.status_code not in {200, 206}:
                    continue
                data = bytearray()
                for chunk in response.iter_content(chunk_size=65536):
                    data.extend(chunk)
                    if len(data) >= 262144:
                        break
            if not data:
                continue
            with tempfile.TemporaryDirectory(prefix="bili_probe_") as folder:
                sample = Path(folder) / "sample.mp4"
                sample.write_bytes(data)
                probe = subprocess.run(
                    ["ffprobe", "-v", "error", "-select_streams", "v:0",
                     "-show_entries", "stream=width,height,r_frame_rate,codec_name",
                     "-of", "json", str(sample)],
                    capture_output=True, text=True, encoding="utf-8", errors="replace",
                    creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
                )
            if probe.returncode == 0:
                stream = (json.loads(probe.stdout).get("streams") or [{}])[0]
                fps = float(Fraction(stream.get("r_frame_rate") or "0/1"))
                codec = "H.265" if stream.get("codec_name") == "hevc" else "H.264"
                return int(stream.get("width") or 0), int(stream.get("height") or 0), fps, codec
        except (requests.RequestException, OSError, ValueError, IndexError, ZeroDivisionError):
            continue
    return 0, QUALITY_HEIGHT.get(quality_id, 0), 0.0, "H.264"


def collect_options(session: requests.Session, share_url: str) -> list[dict]:
    bvid, page = video_identity(session, share_url)
    referer = f"https://www.bilibili.com/video/{bvid}/"
    session.headers["Referer"] = referer
    view = api_data(session, "/x/web-interface/view", {"bvid": bvid})
    pages = view.get("pages") or []
    if pages and page > len(pages):
        raise DownloadError(f"这个视频只有 {len(pages)} 个分 P，链接指定了第 {page} P")
    if not pages and page > 1:
        raise DownloadError("这个视频没有所指定的分 P")
    cid = pages[page - 1]["cid"] if pages else view.get("cid")
    if not cid:
        raise DownloadError("视频详情中没有 CID")
    duration = int((pages[page - 1].get("duration") if pages else view.get("duration")) or 0)
    options = []
    seen_mp4 = set()

    top = api_data(session, "/x/player/playurl",
                   {"bvid": bvid, "cid": cid, "qn": 127, "fnval": 0, "fourk": 1})
    highest = int(top.get("quality") or 0)
    requests_for_mp4 = [(127, top)]
    for qn in QUALITY_IDS:
        if qn >= highest or qn == 127:
            continue
        try:
            data = api_data(session, "/x/player/playurl",
                            {"bvid": bvid, "cid": cid, "qn": qn, "fnval": 0, "fourk": 1})
            requests_for_mp4.append((qn, data))
        except DownloadError:
            continue
    for _, data in requests_for_mp4:
        actual = int(data.get("quality") or 0)
        segments = data.get("durl") or []
        if actual in seen_mp4 or len(segments) != 1 or "mp4" not in (data.get("format") or ""):
            continue
        urls = address_urls(segments[0])
        if not urls:
            continue
        seen_mp4.add(actual)
        width, height, fps, codec = probe_mp4_info(session, urls, actual)
        options.append({
            "id": f"bili:mp4:{actual}", "platform": "bilibili", "mode": "mp4",
            "width": width, "height": height, "fps": fps, "codec": codec,
            "bytes": int(segments[0].get("size") or 0), "bitrate": 0,
            "urls": urls, "audio_urls": [], "bvid": bvid, "cid": cid, "page": page,
            "source_url": share_url,
        })

    try:
        dash_data = api_data(session, "/x/player/playurl",
                             {"bvid": bvid, "cid": cid, "qn": 127, "fnval": 4048,
                              "fourk": 1, "try_look": 1})
        dash = dash_data.get("dash") or {}
        audio_items = [item for item in dash.get("audio", []) if
                       (item.get("codecs") or "").startswith("mp4a") and address_urls(item)]
        audio = max(audio_items, key=lambda item: int(item.get("bandwidth") or 0), default=None)
        if audio:
            for item in dash.get("video", []):
                codec_name = item.get("codecs") or ""
                codec = ("H.264" if codec_name.startswith("avc1") else
                         "H.265" if codec_name.startswith(("hvc1", "hev1")) else
                         "AV1" if codec_name.startswith("av01") else None)
                urls = address_urls(item)
                if not codec or not urls:
                    continue
                width, height = int(item.get("width") or 0), int(item.get("height") or 0)
                fps = float(item.get("frame_rate") or 0)
                if fps and abs(fps - round(fps)) < 0.1:
                    fps = float(round(fps))
                bitrate = int(item.get("bandwidth") or 0)
                audio_bitrate = int(audio.get("bandwidth") or 0)
                options.append({
                    "id": f"bili:dash:{item.get('id')}:{codec_name}:{bitrate}",
                    "platform": "bilibili", "mode": "dash",
                    "width": width, "height": height, "fps": fps, "codec": codec,
                    "bytes": round((bitrate + audio_bitrate) * duration / 8) if duration else 0,
                    "bitrate": bitrate, "urls": urls, "audio_urls": address_urls(audio),
                    "bvid": bvid, "cid": cid, "page": page, "source_url": share_url,
                })
    except DownloadError:
        pass

    options.sort(key=lambda item: (item["width"] * item["height"], item["fps"],
                                   item["codec"] == "H.264", item["bitrate"]), reverse=True)
    if not options:
        raise DownloadError("公开接口没有返回可下载的播放流")
    return options


def list_qualities(text: str) -> list[dict]:
    share_url = extract_url(text)
    if ident := opus_identity(share_url):
        return [collect_opus(ident, share_url)]
    with new_session(share_url) as session:
        return collect_options(session, share_url)


def probe_total(session: requests.Session, urls: list[str]) -> tuple[str, int]:
    for url in valid_media_urls(urls):
        try:
            response = session.get(url, headers={"Range": "bytes=0-0"}, timeout=12)
            match = CONTENT_RANGE.fullmatch(response.headers.get("Content-Range", ""))
            if response.status_code == 206 and match:
                return url, int(match.group(3))
        except requests.RequestException:
            continue
    raise DownloadError("播放地址已失效或媒体服务器不可访问")


def download_range(session: requests.Session, url: str, total: int, path: Path,
                   phase: str, progress: Progress) -> None:
    written = 0
    with path.open("wb") as output:
        while written < total:
            end = min(written + CHUNK_SIZE, total) - 1
            last_error = None
            for _ in range(3):
                try:
                    response = session.get(url, headers={"Range": f"bytes={written}-{end}"}, timeout=(10, 40))
                    match = CONTENT_RANGE.fullmatch(response.headers.get("Content-Range", ""))
                    if response.status_code != 206 or not match:
                        raise DownloadError(f"媒体请求返回 HTTP {response.status_code}")
                    start, actual_end, actual_total = map(int, match.groups())
                    if (start, actual_end, actual_total) != (written, end, total) or len(response.content) != end - written + 1:
                        raise DownloadError("媒体分段长度与服务器声明不一致")
                    output.write(response.content)
                    written += len(response.content)
                    progress(phase, written, total)
                    break
                except (requests.RequestException, DownloadError) as error:
                    last_error = error
            else:
                raise DownloadError(f"{phase}失败：{last_error}")


def inspect_output(path: Path) -> dict:
    result = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries",
         "stream=codec_type,codec_name,width,height,r_frame_rate:format=duration",
         "-of", "json", str(path)],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
    )
    if result.returncode:
        raise DownloadError(f"输出视频无法识别：{result.stderr[:200]}")
    return json.loads(result.stdout)


def unique_output(folder: Path, option: dict) -> Path:
    quality = f"{min(option['width'], option['height'])}p" if option["width"] else "video"
    codec = option["codec"].lower().replace(".", "")
    base = f"{option['bvid']}_{quality}_{codec}"
    if option["page"] > 1:
        base += f"_p{option['page']}"
    path = folder / f"{base}.mp4"
    index = 2
    while path.exists():
        path = folder / f"{base}_{index}.mp4"
        index += 1
    return path


def save_video(text: str, output_folder: Path, progress: Progress,
               quality: str = "1080", cached_option: dict | None = None) -> Path:
    share_url = extract_url(text)
    if ident := opus_identity(share_url):
        option = (cached_option if cached_option and cached_option.get("kind") == "images"
                  and cached_option.get("source_url") == share_url else collect_opus(ident, share_url))
        with new_session(f"https://www.bilibili.com/opus/{ident}") as image_session:
            def fetch_image(address: str) -> tuple[bytes, str]:
                response = image_session.get(address, timeout=30)
                response.raise_for_status()
                return response.content, response.headers.get("content-type", "")
            return save_gallery(output_folder, "哔哩哔哩", ident, option["images"],
                                option["caption"], share_url, fetch_image, progress,
                                option.get("author", ""), option.get("published", ""),
                                option.get("selected_indices"), option.get("image_cache"))
    output_folder.mkdir(parents=True, exist_ok=True)
    with new_session(share_url) as session:
        option = None
        if cached_option and cached_option.get("source_url") == share_url and cached_option.get("id") == quality:
            option = cached_option
        if option:
            session.headers["Referer"] = f"https://www.bilibili.com/video/{option['bvid']}/"
            try:
                video_url, video_total = probe_total(session, option["urls"])
                audio = probe_total(session, option["audio_urls"]) if option["mode"] == "dash" else None
            except DownloadError:
                option = None
        if not option:
            progress("读取哔哩哔哩画质", 0, 1)
            options = collect_options(session, share_url)
            if quality == "best":
                option = options[0]
            elif quality == "1080":
                compatible = [item for item in options if item["codec"] == "H.264" and
                              min(item["width"], item["height"]) <= 1080]
                option = (compatible or options)[0]
            else:
                option = next((item for item in options if item["id"] == quality), None)
                if not option:
                    raise DownloadError("所选画质暂不可用，请重新读取画质列表")
            video_url, video_total = probe_total(session, option["urls"])
            audio = probe_total(session, option["audio_urls"]) if option["mode"] == "dash" else None
        progress(f"找到 {option['width']}×{option['height']} · {option['fps']:g} 帧/秒 播放流", 1, 1)

        with tempfile.TemporaryDirectory(prefix="bilibili_", dir=output_folder) as temp_name:
            temp = Path(temp_name)
            video_part = temp / ("video.m4s" if audio else "video.mp4")
            download_range(session, video_url, video_total, video_part, "下载视频", progress)
            destination = unique_output(output_folder, option)
            if audio:
                audio_part = temp / "audio.m4s"
                download_range(session, audio[0], audio[1], audio_part, "下载音频", progress)
                progress("合并音视频", 0, 1)
                result = subprocess.run(
                    ["ffmpeg", "-hide_banner", "-loglevel", "error", "-i", str(video_part),
                     "-i", str(audio_part), "-map", "0:v:0", "-map", "1:a:0", "-c", "copy",
                     "-movflags", "+faststart", str(destination)],
                    capture_output=True, text=True, encoding="utf-8", errors="replace",
                    creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
                )
                if result.returncode:
                    destination.unlink(missing_ok=True)
                    raise DownloadError(f"音视频合并失败：{result.stderr[:300]}")
            else:
                shutil.move(str(video_part), str(destination))
            info = inspect_output(destination)
            if not any(stream.get("codec_type") == "video" for stream in info.get("streams", [])):
                destination.unlink(missing_ok=True)
                raise DownloadError("输出文件缺少视频轨")
            progress("完成", 1, 1)
            return destination
