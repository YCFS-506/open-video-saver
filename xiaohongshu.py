"""Read and save video streams exposed by a public Xiaohongshu note page."""

from __future__ import annotations

import json
import re
import shutil
import tempfile
from datetime import datetime
from pathlib import Path
from typing import Callable
from urllib.parse import urlparse

import requests
from playwright.sync_api import Error as PlaywrightError
from playwright.sync_api import TimeoutError as PlaywrightTimeoutError
from playwright.sync_api import sync_playwright

from bilibili import download_range, inspect_output, probe_mp4_info, probe_total
from platforms import DownloadError, extract_url


Progress = Callable[[str, int, int], None]
NOTE_ID = re.compile(r"/(?:explore|discovery/item)/([0-9a-f]{24})(?:/|$)", re.IGNORECASE)
MEDIA_HOST = re.compile(r"(?:^|\.)xhscdn\.com$", re.IGNORECASE)


def media_urls(item: dict) -> list[str]:
    urls = [item.get("masterUrl") or ""] + (item.get("backupUrls") or [])
    result = []
    for url in urls:
        parsed = urlparse(url)
        if parsed.scheme not in {"http", "https"} or not MEDIA_HOST.search(parsed.hostname or ""):
            continue
        # The page often supplies HTTP addresses; the CDN also serves HTTPS.
        secure = parsed._replace(scheme="https").geturl()
        if secure not in result:
            result.append(secure)
    return result


def read_note(share_url: str) -> tuple[str, dict]:
    try:
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(
                channel="msedge", headless=False,
                args=["--window-position=-2000,-2000"],
            )
            try:
                context = browser.new_context(locale="zh-CN")
                page = context.new_page()
                page.goto(share_url, wait_until="domcontentloaded", timeout=30000)
                match = NOTE_ID.search(urlparse(page.url).path)
                if not match:
                    raise DownloadError("小红书未打开笔记页；若跳到登录或安全验证，请先在浏览器确认此链接可播放")
                note_id = match.group(1).lower()
                try:
                    page.wait_for_function(
                        "id => Boolean(window.__INITIAL_STATE__?.note?.noteDetailMap?.[id]?.note)",
                        arg=note_id, timeout=15000,
                    )
                except PlaywrightTimeoutError as error:
                    raise DownloadError("小红书页面没有返回笔记信息；可能需要登录或完成安全验证") from error
                note = page.evaluate(
                    "id => window.__INITIAL_STATE__.note.noteDetailMap[id].note", note_id,
                )
                return note_id, note
            finally:
                browser.close()
    except DownloadError:
        raise
    except PlaywrightError as error:
        raise DownloadError(f"小红书页面访问失败：{error}") from error


def collect_options(share_url: str) -> list[dict]:
    note_id, note = read_note(share_url)
    if note.get("type") != "video":
        images = []
        for image in note.get("imageList") or []:
            candidates = []
            for raw in ([image.get("urlDefault"), image.get("url"),
                         *(item.get("url") for item in image.get("infoList") or []
                           if item.get("imageScene") == "WB_DFT"),
                         image.get("urlPre")]):
                if not isinstance(raw, str):
                    continue
                parsed = urlparse(raw)
                if parsed.scheme in {"http", "https"} and MEDIA_HOST.search(parsed.hostname or ""):
                    address = parsed._replace(scheme="https").geturl()
                    if address not in candidates:
                        candidates.append(address)
            if candidates:
                images.append(candidates)
        if not images:
            raise DownloadError("这条小红书图文笔记没有返回可下载的图片")
        title = (note.get("title") or "").strip()
        description = (note.get("desc") or "").strip()
        caption = title + ("\n\n" + description if description and description != title else "")
        timestamp = int(note.get("time") or 0)
        published = (datetime.fromtimestamp(timestamp / 1000).strftime("%Y-%m-%d %H:%M:%S")
                     if timestamp else "")
        return [{
            "id": f"xhs:images:{note_id}", "platform": "xiaohongshu", "kind": "images",
            "width": 0, "height": 0, "fps": 0, "codec": "IMAGE", "bytes": 0,
            "image_count": len(images), "images": images, "caption": caption,
            "author": (note.get("user") or {}).get("nickname") or "",
            "published": published, "note_id": note_id, "source_url": share_url,
        }]
    video_data = note.get("video") or {}
    stream = ((video_data.get("media") or {}).get("stream") or {})
    options = []
    seen = set()
    for items in stream.values():
        for item in items or []:
            urls = media_urls(item)
            width, height = int(item.get("width") or 0), int(item.get("height") or 0)
            name = (item.get("videoCodec") or "").lower()
            codec = ("H.264" if name in {"h264", "avc"} else
                     "H.265" if name in {"h265", "hevc"} else
                     "AV1" if name == "av1" else None)
            size = int(item.get("size") or 0)
            key = (width, height, codec, size)
            if not urls or not width or not height or not codec or key in seen:
                continue
            seen.add(key)
            options.append({
                "id": f"xhs:{width}x{height}:{codec}:{size}",
                "platform": "xiaohongshu", "width": width, "height": height,
                "fps": float(item.get("fps") or 0), "codec": codec,
                "bytes": size, "bitrate": int(item.get("videoBitrate") or 0),
                "urls": urls, "note_id": note_id, "source_url": share_url,
                "duration": float(item.get("duration") or 0) / 1000,
                "audio_codec": item.get("audioCodec") or "",
            })
    # mediaV2 also exposes a separate HD screencast URL. It can be a different
    # codec and resolution from media.stream, so inspect the actual MP4 header.
    media_v2 = video_data.get("mediaV2") or {}
    if isinstance(media_v2, str):
        try:
            media_v2 = json.loads(media_v2)
        except ValueError:
            media_v2 = {}
    hd_video = (media_v2.get("video") or {}) if isinstance(media_v2, dict) else {}
    hd_url = (hd_video.get("opaque1") or {}).get("hd_screencast_stream")
    hd_urls = media_urls({"masterUrl": hd_url}) if hd_url else []
    if hd_urls:
        with requests.Session() as session:
            session.headers.update({
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/138.0.0.0 Safari/537.36 Edg/138.0.0.0",
                "Referer": "https://www.xiaohongshu.com/",
            })
            width, height, fps, codec = probe_mp4_info(session, hd_urls, 80)
            try:
                _, total = probe_total(session, hd_urls)
            except DownloadError:
                total = 0
        key = (width, height, codec, total)
        if width and height and total and key not in seen:
            seen.add(key)
            duration = float(hd_video.get("duration") or 0)
            options.append({
                "id": f"xhs:{width}x{height}:{codec}:{total}",
                "platform": "xiaohongshu", "width": width, "height": height,
                "fps": fps, "codec": codec, "bytes": total,
                "bitrate": round(total * 8 / duration) if duration else 0,
                "urls": hd_urls, "note_id": note_id, "source_url": share_url,
                "duration": duration, "audio_codec": "",
            })
    options.sort(key=lambda item: (
        item["width"] * item["height"], item["fps"],
        item["codec"] == "H.264", item["bitrate"],
    ), reverse=True)
    if not options:
        raise DownloadError("这条小红书笔记没有返回可下载的视频播放流")
    return options


def list_qualities(text: str) -> list[dict]:
    return collect_options(extract_url(text))


def unique_output(folder: Path, option: dict) -> Path:
    label = min(option["width"], option["height"])
    if 1070 <= label <= 1080:
        label = 1080
    base = f"{option['note_id']}_{label}p_{option['codec'].lower().replace('.', '')}"
    path = folder / f"{base}.mp4"
    index = 2
    while path.exists():
        path = folder / f"{base}_{index}.mp4"
        index += 1
    return path


def save_video(text: str, output_folder: Path, progress: Progress,
               quality: str = "1080", cached_option: dict | None = None) -> Path:
    share_url = extract_url(text)
    output_folder.mkdir(parents=True, exist_ok=True)
    session = requests.Session()
    session.headers.update({
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/138.0.0.0 Safari/537.36 Edg/138.0.0.0",
        "Referer": "https://www.xiaohongshu.com/",
    })
    try:
        option = None
        if (cached_option and cached_option.get("source_url") == share_url and
                cached_option.get("kind") == "images"):
            option = cached_option
        if option and option.get("kind") == "images":
            return save_image_note(option, output_folder, session, progress)
        if (cached_option and cached_option.get("source_url") == share_url and
                cached_option.get("id") == quality):
            option = cached_option
            try:
                media_url, total = probe_total(session, option["urls"])
            except DownloadError:
                option = None
        if not option:
            progress("读取小红书画质", 0, 1)
            options = collect_options(share_url)
            if options[0].get("kind") == "images":
                return save_image_note(options[0], output_folder, session, progress)
            if quality == "best":
                option = options[0]
            elif quality == "1080":
                compatible = [item for item in options if
                              min(item["width"], item["height"]) <= 1080]
                option = (compatible or options)[0]
            else:
                option = next((item for item in options if item["id"] == quality), None)
                if not option:
                    raise DownloadError("所选画质暂不可用，请重新读取画质列表")
            media_url, total = probe_total(session, option["urls"])
        progress(f"找到 {option['width']}×{option['height']} · {option['fps']:g} 帧/秒 播放流", 1, 1)
        with tempfile.TemporaryDirectory(prefix="xiaohongshu_", dir=output_folder) as temp_name:
            part = Path(temp_name) / "video.mp4"
            download_range(session, media_url, total, part, "下载视频", progress)
            info = inspect_output(part)
            streams = info.get("streams", [])
            video = next((stream for stream in streams if stream.get("codec_type") == "video"), None)
            if not video:
                raise DownloadError("下载文件缺少视频轨")
            if option["audio_codec"] and not any(s.get("codec_type") == "audio" for s in streams):
                raise DownloadError("下载文件缺少音频轨")
            if option["duration"] and float(info.get("format", {}).get("duration") or 0) < option["duration"] * 0.9:
                raise DownloadError("下载文件时长不足，可能只是试看片段")
            destination = unique_output(output_folder, option)
            # Copy out of TemporaryDirectory so the file inherits the user's
            # chosen output folder permissions on Windows.
            try:
                shutil.copyfile(part, destination)
            except OSError:
                destination.unlink(missing_ok=True)
                raise
            progress("完成", 1, 1)
            return destination
    finally:
        session.close()


def save_image_note(option: dict, output_folder: Path, session: requests.Session,
                    progress: Progress) -> Path:
    from image_posts import save_gallery

    def fetch_image(address: str) -> tuple[bytes, str]:
        response = session.get(address, timeout=30)
        response.raise_for_status()
        return response.content, response.headers.get("content-type", "")

    return save_gallery(output_folder, "小红书", option["note_id"], option["images"],
                        option["caption"], option["source_url"], fetch_image, progress,
                        option.get("author", ""), option.get("published", ""),
                        option.get("selected_indices"), option.get("image_cache"))
