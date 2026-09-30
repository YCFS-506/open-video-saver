"""Save the playback formats returned by a public Kuaishou video page."""

from __future__ import annotations

import os
import json
import re
import shutil
import subprocess
import tempfile
import time
from datetime import datetime
from pathlib import Path
from typing import Callable
from urllib.parse import urljoin, urlparse

import requests
from playwright.sync_api import Error as PlaywrightError
from playwright.sync_api import sync_playwright

from bilibili import download_range, inspect_output, probe_mp4_info, probe_total
from platforms import DownloadError, extract_url


Progress = Callable[[str, int, int], None]
VIDEO_ID = re.compile(r"/short-video/([0-9a-z]+)(?:/|$)", re.IGNORECASE)
MOBILE_VIDEO_ID = re.compile(r"/fw/photo/([0-9a-z]+)(?:/|$)", re.IGNORECASE)
MEDIA_DOMAINS = ("kwaicdn.com", "gifshow.com", "yximgs.com", "ksyun.com")
USER_AGENT = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36 Edg/138.0.0.0")
PUBLIC_LINK_API = "https://api.bugpk.com/api/kuaishou"
MOBILE_USER_AGENT = ("Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) "
                     "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 "
                     "Mobile/15E148 Safari/604.1 Edg/122.0.0.0")


def new_session() -> requests.Session:
    session = requests.Session()
    session.trust_env = False
    session.headers.update({"User-Agent": USER_AGENT, "Referer": "https://www.kuaishou.com/"})
    return session


def resolve_video(session: requests.Session, share_url: str) -> tuple[str, str]:
    try:
        response = session.get(share_url, timeout=20)
        response.raise_for_status()
    except requests.RequestException as error:
        raise DownloadError(f"快手分享链接访问失败：{error}") from error
    parsed = urlparse(response.url)
    if parsed.hostname not in {"www.kuaishou.com", "kuaishou.com"}:
        raise DownloadError("快手短链未跳转到快手作品页")
    match = VIDEO_ID.search(parsed.path)
    if not match:
        raise DownloadError("快手短链没有返回可识别的作品 ID")
    return match.group(1), response.url


def find_photo(node: object, video_id: str) -> dict | None:
    if isinstance(node, dict):
        if (node.get("id") == video_id and
                (node.get("photoUrl") or node.get("photoH265Url") or node.get("videoResource"))):
            return node
        for value in node.values():
            found = find_photo(value, video_id)
            if found:
                return found
    elif isinstance(node, list):
        for value in node:
            found = find_photo(value, video_id)
            if found:
                return found
    return None


def read_photo(share_url: str, video_id: str, verification_timeout: int = 120) -> dict:
    profile = Path(os.environ.get("LOCALAPPDATA") or tempfile.gettempdir()) / "OpenVideoSaver" / "KuaishouBrowserDirect"
    profile.parent.mkdir(parents=True, exist_ok=True)
    found: list[dict] = []
    challenged = False
    try:
        with sync_playwright() as playwright:
            context = playwright.chromium.launch_persistent_context(
                str(profile), channel="msedge", headless=False,
                locale="zh-CN", args=["--window-size=1200,800", "--no-proxy-server"],
            )
            try:
                page = context.pages[0] if context.pages else context.new_page()

                def on_response(response) -> None:
                    nonlocal challenged
                    if "/graphql" not in response.url:
                        return
                    try:
                        data = response.json()
                    except (ValueError, PlaywrightError):
                        return
                    photo = find_photo(data.get("data"), video_id)
                    if photo:
                        found.append(photo)
                    if any("Need captcha" in str(error.get("message")) for error in data.get("errors") or []):
                        challenged = True

                page.on("response", on_response)
                page.goto(share_url, wait_until="domcontentloaded", timeout=30000)
                deadline = time.monotonic() + verification_timeout
                last_reload = time.monotonic()
                captcha_was_visible = False
                while time.monotonic() < deadline:
                    if found:
                        return found[0]
                    try:
                        state = page.evaluate("() => window.__APOLLO_STATE__?.defaultClient || {}")
                    except PlaywrightError as error:
                        if "Execution context was destroyed" in str(error):
                            page.wait_for_timeout(500)
                            continue
                        raise
                    photo = find_photo(state, video_id)
                    if photo:
                        return photo
                    # A completed manual slider may remove its iframe without retrying
                    # the failed detail request. Reload the public page once it clears.
                    has_captcha = any("captcha.zt.kuaishou.com" in frame.url
                                      for frame in page.frames)
                    if has_captcha:
                        captcha_was_visible = True
                    if challenged and captcha_was_visible and not has_captcha and time.monotonic() - last_reload > 2:
                        challenged = False
                        captcha_was_visible = False
                        page.reload(wait_until="domcontentloaded", timeout=30000)
                        last_reload = time.monotonic()
                    page.wait_for_timeout(1000)
                if challenged:
                    raise DownloadError("快手要求滑块验证。请在弹出的 Edge 窗口手动完成验证，然后重新读取画质")
                raise DownloadError("快手作品页没有返回播放地址；请确认该视频在浏览器中能正常播放")
            finally:
                context.close()
    except DownloadError:
        raise
    except PlaywrightError as error:
        raise DownloadError(f"快手页面访问失败：{error}") from error


def media_url(url: str) -> str | None:
    parsed = urlparse(url)
    host = (parsed.hostname or "").lower()
    if parsed.scheme not in {"http", "https"} or not any(
            host == domain or host.endswith("." + domain) for domain in MEDIA_DOMAINS):
        return None
    return parsed._replace(scheme="https").geturl()


def mobile_options(share_url: str) -> list[dict]:
    """Read the public mobile page's own playback manifest without login."""
    with new_session() as session:
        session.headers.update({"User-Agent": MOBILE_USER_AGENT,
                                "Referer": "https://m.gifshow.com/"})
        try:
            response = session.get(share_url, timeout=20)
            response.raise_for_status()
        except requests.RequestException:
            return []
        parsed = urlparse(response.url)
        host = parsed.hostname or ""
        if host != "m.gifshow.com" and not host.endswith(".m.chenzhongtech.com"):
            return []
        match = MOBILE_VIDEO_ID.search(parsed.path)
        if not match:
            return []
        video_id = match.group(1)
        page = response.content.decode("utf-8", "replace")
        match_state = re.search(r"window\.INIT_STATE\s*=\s*", page)
        if not match_state:
            return []
        try:
            state, _ = json.JSONDecoder().raw_decode(page[match_state.end():])
        except ValueError:
            return []
        atlas_entry = next((value for value in state.values()
                            if isinstance(value, dict) and isinstance(value.get("atlas"), dict)
                            and (value["atlas"].get("list") or [])), None)
        if atlas_entry:
            atlas = atlas_entry["atlas"]
            photo = atlas_entry.get("photo") or {}
            cdns = atlas.get("cdn") or []
            if isinstance(cdns, str):
                cdns = [cdns]
            cdns.extend(item.get("cdn") for item in atlas.get("cdnList") or []
                        if isinstance(item, dict))
            images = []
            for path in atlas.get("list") or []:
                if not isinstance(path, str) or not path.startswith("/ufile/atlas/"):
                    continue
                candidates = []
                for cdn in cdns:
                    if isinstance(cdn, str):
                        address = media_url(f"https://{cdn}{path}")
                        if address and address not in candidates:
                            candidates.append(address)
                if candidates:
                    images.append(candidates)
            if images:
                timestamp = int(photo.get("timestamp") or 0)
                published = (datetime.fromtimestamp(timestamp / 1000).strftime("%Y-%m-%d %H:%M:%S")
                             if timestamp else "")
                return [{
                    "id": f"ks:images:{video_id}", "platform": "kuaishou", "kind": "images",
                    "width": 0, "height": 0, "fps": 0, "codec": "IMAGE", "bytes": 0,
                    "image_count": len(images), "images": images,
                    "caption": (photo.get("caption") or "").strip(),
                    "author": photo.get("userName") or "", "published": published,
                    "video_id": video_id, "source_url": share_url,
                }]
        photo = next((value["photo"] for value in state.values()
                      if isinstance(value, dict) and isinstance(value.get("photo"), dict)
                      and value["photo"].get("manifest")), None)
        if not photo:
            return []
        duration = float(photo.get("duration") or 0) / 1000
        options = []
        for adaptation in (photo.get("manifest") or {}).get("adaptationSet") or []:
            for representation in adaptation.get("representation") or []:
                url = media_url(representation.get("url") or "")
                if not url or not urlparse(url).path.endswith(".m3u8"):
                    continue
                width = int(representation.get("width") or 0)
                height = int(representation.get("height") or 0)
                if not width or not height:
                    continue
                codec = "H.265" if representation.get("videoCodec") == "hevc" else "H.264"
                bitrate = int(representation.get("avgBitrate") or 0) * 1000
                quality_label = representation.get("qualityType") or ""
                options.append({
                    "id": f"ks:hls:{width}x{height}:{codec}:{representation.get('id')}",
                    "platform": "kuaishou", "width": width, "height": height,
                    "fps": float(representation.get("frameRate") or 0), "codec": codec,
                    "bytes": round(bitrate * duration / 8) if duration else 0,
                    "bitrate": bitrate, "urls": [url], "video_id": video_id,
                    "source_url": share_url, "duration": duration, "hls": True,
                    "quality_label": quality_label,
                })
        options.sort(key=lambda item: (item["width"] * item["height"], item["bitrate"]), reverse=True)
        return options


def formats_from_photo(photo: dict, video_id: str, share_url: str,
                       session: requests.Session) -> list[dict]:
    candidates: list[tuple[str, str]] = []
    for field, codec in (("photoUrl", "h264"), ("photoH265Url", "hevc")):
        if photo.get(field):
            candidates.append((photo[field], codec))
    resource = photo.get("videoResource") or {}
    if isinstance(resource, str):
        try:
            resource = json.loads(resource)
        except ValueError:
            resource = {}
    if not isinstance(resource, dict):
        resource = {}
    if isinstance(resource.get("json"), str):
        try:
            resource = json.loads(resource["json"])
        except ValueError:
            resource = {}
    if isinstance(resource.get("json"), dict):
        resource = resource["json"]
    for codec in ("h264", "hevc"):
        for adaptation in (resource.get(codec) or {}).get("adaptationSet") or []:
            for representation in adaptation.get("representation") or []:
                if representation.get("url"):
                    candidates.append((representation["url"], codec))

    options = []
    seen_urls = set()
    seen_formats = set()
    duration = float(photo.get("duration") or 0) / 1000
    for raw_url, expected_codec in candidates:
        url = media_url(raw_url)
        if not url or url in seen_urls:
            continue
        seen_urls.add(url)
        try:
            width, height, fps, codec = probe_mp4_info(session, [url], 80)
            _, total = probe_total(session, [url])
        except DownloadError:
            continue
        if not width or not height or not total:
            continue
        # The MP4 header takes precedence over an API codec label.
        key = (width, height, codec, total)
        if key in seen_formats:
            continue
        seen_formats.add(key)
        options.append({
            "id": f"ks:{width}x{height}:{codec}:{total}",
            "platform": "kuaishou", "width": width, "height": height,
            "fps": fps, "codec": codec, "bytes": total,
            "bitrate": round(total * 8 / duration) if duration else 0,
            "urls": [url], "video_id": video_id, "source_url": share_url,
            "duration": duration,
        })
    options.sort(key=lambda item: (item["width"] * item["height"], item["fps"],
                                   item["codec"] == "H.264", item["bitrate"]), reverse=True)
    return options


def public_link_options(session: requests.Session, share_url: str,
                        video_id: str) -> list[dict]:
    """Ask a public-link resolver for one playback stream; no cookies are sent."""
    try:
        response = session.get(PUBLIC_LINK_API, params={"url": share_url}, timeout=12)
        response.raise_for_status()
        result = response.json()
    except (requests.RequestException, ValueError):
        return []
    if result.get("code") != 200:
        return []
    url = (result.get("data") or {}).get("url")
    if not isinstance(url, str) or not media_url(url):
        return []
    options = formats_from_photo({"photoUrl": url}, video_id, share_url, session)
    for option in options:
        option["via_public_link_service"] = True
    return options


def write_hls_playlist(session: requests.Session, url: str, path: Path) -> None:
    """Make remote HLS paths absolute and fix the range on distinct fragments."""
    try:
        response = session.get(url, timeout=20)
        response.raise_for_status()
    except requests.RequestException as error:
        raise DownloadError(f"快手高清播放清单读取失败：{error}") from error
    lines = response.text.splitlines()
    if not lines or lines[0].strip() != "#EXTM3U":
        raise DownloadError("快手高清播放清单格式不正确")
    segments = [line for line in lines if line and not line.startswith("#")]
    absolute_segments = [media_url(urljoin(url, line)) for line in segments]
    if not segments or any(not item for item in absolute_segments):
        raise DownloadError("快手高清播放清单含有不可信的分片地址")
    range_lines = [line for line in lines if line.startswith("#EXT-X-BYTERANGE:")]
    # This mobile manifest marks only the first fragment as a byte range.
    # Leaving that marker in place makes FFmpeg reuse the offset for later
    # independent files, so the CDN returns 416 and the output ends early.
    remove_first_range = (len(range_lines) == 1 and
                          len(set(absolute_segments)) == len(absolute_segments))
    rewritten = []
    for line in lines:
        if remove_first_range and line.startswith("#EXT-X-BYTERANGE:"):
            continue
        if line.startswith("#EXT-X-KEY:"):
            raise DownloadError("暂不支持此快手视频的加密播放清单")
        if line.startswith("#EXT-X-MAP:"):
            def absolute_map(match: re.Match) -> str:
                mapped = media_url(urljoin(url, match.group(1)))
                if not mapped:
                    raise DownloadError("快手高清播放清单含有不可信的初始化地址")
                return f'URI="{mapped}"'
            line = re.sub(r'URI="([^"]+)"', absolute_map, line)
        elif line and not line.startswith("#"):
            line = media_url(urljoin(url, line)) or ""
        rewritten.append(line)
    path.write_text("\n".join(rewritten) + "\n", encoding="utf-8")


def collect_options(share_url: str, verification_timeout: int = 120) -> list[dict]:
    options = mobile_options(share_url)
    if options:
        return options
    with new_session() as session:
        video_id, _ = resolve_video(session, share_url)
        options = public_link_options(session, share_url, video_id)
        if options:
            return options
        photo = read_photo(share_url, video_id, verification_timeout)
        options = formats_from_photo(photo, video_id, share_url, session)
    if not options:
        raise DownloadError("快手返回了作品详情，但没有可访问的播放流")
    return options


def list_qualities(text: str) -> list[dict]:
    return collect_options(extract_url(text))


def unique_output(folder: Path, option: dict) -> Path:
    label = option.get("quality_label") or f"{min(option['width'], option['height'])}p"
    label = re.sub(r"[^a-zA-Z0-9_-]", "_", label)
    base = f"{option['video_id']}_{label}_{option['codec'].lower().replace('.', '')}"
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
    with new_session() as session:
        option = None
        if (cached_option and cached_option.get("source_url") == share_url and
                cached_option.get("id") == quality):
            option = cached_option
            if option.get("kind") == "images":
                return save_image_atlas(option, output_folder, session, progress)
            if not option.get("hls"):
                try:
                    media, total = probe_total(session, option["urls"])
                except DownloadError:
                    option = None
        if not option:
            progress("读取快手作品信息", 0, 1)
            options = collect_options(share_url)
            if options[0].get("kind") == "images":
                return save_image_atlas(options[0], output_folder, session, progress)
            if quality == "best":
                option = options[0]
            elif quality == "1080":
                within = [item for item in options if min(item["width"], item["height"]) <= 1080]
                option = (within or options)[0]
            else:
                option = next((item for item in options if item["id"] == quality), None)
                if not option:
                    raise DownloadError("所选画质暂不可用，请重新读取画质列表")
            if not option.get("hls"):
                media, total = probe_total(session, option["urls"])
        progress(f"找到 {option['width']}×{option['height']} · {option['fps']:g} 帧/秒 播放流", 1, 1)
        with tempfile.TemporaryDirectory(prefix="kuaishou_", dir=output_folder) as temp_name:
            part = Path(temp_name) / "video.mp4"
            if option.get("hls"):
                progress("下载高清播放流", 0, 1)
                playlist = Path(temp_name) / "playlist.m3u8"
                write_hls_playlist(session, option["urls"][0], playlist)
                result = subprocess.run(
                    ["ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                     "-protocol_whitelist", "file,http,https,tcp,tls,crypto",
                     "-allowed_extensions", "ALL", "-i", str(playlist),
                     "-c", "copy", "-movflags", "+faststart", str(part)],
                    capture_output=True, text=True, encoding="utf-8", errors="replace",
                    creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
                )
                if result.returncode:
                    raise DownloadError(f"快手高清流下载失败：{result.stderr[-250:]}")
            else:
                download_range(session, media, total, part, "下载视频", progress)
            info = inspect_output(part)
            if not any(s.get("codec_type") == "video" for s in info.get("streams", [])):
                raise DownloadError("下载文件缺少视频轨")
            if option["duration"] and float(info.get("format", {}).get("duration") or 0) < option["duration"] * 0.9:
                raise DownloadError("下载文件时长不足，可能只是试看片段")
            destination = unique_output(output_folder, option)
            try:
                shutil.copyfile(part, destination)
            except OSError:
                destination.unlink(missing_ok=True)
                raise
            progress("完成", 1, 1)
            return destination


def save_image_atlas(option: dict, output_folder: Path, session: requests.Session,
                     progress: Progress) -> Path:
    from image_posts import save_gallery

    def fetch_image(address: str) -> tuple[bytes, str]:
        response = session.get(address, timeout=30)
        response.raise_for_status()
        return response.content, response.headers.get("content-type", "")

    return save_gallery(output_folder, "快手", option["video_id"], option["images"],
                        option["caption"], option["source_url"], fetch_image, progress,
                        option.get("author", ""), option.get("published", ""),
                        option.get("selected_indices"), option.get("image_cache"))
