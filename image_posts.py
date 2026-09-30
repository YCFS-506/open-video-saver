"""Store a public image post as ordered images and plain text metadata."""

from __future__ import annotations

import re
import io
import shutil
import tempfile
from pathlib import Path
from typing import Callable

import requests
from PIL import Image

from platforms import DownloadError


Progress = Callable[[str, int, int], None]
FetchImage = Callable[[str], tuple[bytes, str]]
EXTENSIONS = {
    "image/jpeg": "jpg", "image/png": "png", "image/webp": "webp",
    "image/avif": "avif", "image/gif": "gif", "image/heic": "heic",
}


def unique_folder(parent: Path, name: str) -> Path:
    safe = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", name).strip(" .")[:100]
    path = parent / safe
    number = 2
    while path.exists():
        path = parent / f"{safe}_{number}"
        number += 1
    return path


def save_gallery(output_folder: Path, platform: str, post_id: str,
                 images: list[list[str]], caption: str, source_url: str,
                 fetch: FetchImage, progress: Progress,
                 author: str = "", published: str = "",
                 selected_indices: list[int] | None = None,
                 image_cache: dict[int, tuple[bytes, str]] | None = None) -> Path:
    if not images:
        raise DownloadError("这条图文作品没有可下载的图片")
    chosen = list(range(len(images))) if selected_indices is None else sorted(set(selected_indices))
    if not chosen or any(index < 0 or index >= len(images) for index in chosen):
        raise DownloadError("请至少选择一张有效图片")
    output_folder.mkdir(parents=True, exist_ok=True)
    destination = unique_folder(output_folder, f"{platform}_{post_id}_图文")
    with tempfile.TemporaryDirectory(prefix="image_post_", dir=output_folder) as temp_name:
        temp = Path(temp_name)
        for completed, original_index in enumerate(chosen, 1):
            candidates = images[original_index]
            last_error: Exception | None = None
            for url in ([None] if image_cache and original_index in image_cache else candidates):
                try:
                    data, mime = (image_cache[original_index] if url is None else fetch(url))
                    mime = mime.split(";", 1)[0].lower().strip()
                    extension = EXTENSIONS.get(mime)
                    if not extension or len(data) < 100:
                        raise DownloadError("图片地址没有返回有效的图片文件")
                    (temp / f"{original_index + 1:02d}.{extension}").write_bytes(data)
                    last_error = None
                    break
                except Exception as error:
                    last_error = error
            if last_error:
                raise DownloadError(f"第 {original_index + 1} 张图片下载失败：{last_error}")
            progress("保存图片", completed, len(chosen))
        (temp / "文案.txt").write_text(caption.strip() + "\n", encoding="utf-8-sig")
        lines = [f"平台：{platform}", f"作品 ID：{post_id}",
                 f"已保存图片：{len(chosen)} / {len(images)}",
                 "原作品序号：" + "、".join(str(index + 1) for index in chosen)]
        if author:
            lines.append(f"作者：{author}")
        if published:
            lines.append(f"发布时间：{published}")
        lines.append(f"作品链接：{source_url}")
        (temp / "来源.txt").write_text("\n".join(lines) + "\n", encoding="utf-8-sig")
        shutil.copytree(temp, destination)
    progress("完成", 1, 1)
    return destination


def load_previews(option: dict) -> None:
    """Fetch full image bytes once; thumbnails are only made for the picker UI."""
    platform = option["platform"]
    referer = (f"https://www.douyin.com/note/{option['video_id']}" if platform == "douyin" else
               option["source_url"] if platform == "bilibili" else
               "https://www.xiaohongshu.com/" if platform == "xiaohongshu" else
               "https://www.kuaishou.com/")
    cache = {}
    sizes = {}
    with requests.Session() as session:
        session.trust_env = platform != "kuaishou"
        session.headers.update({"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36",
                                "Referer": referer})
        for index, candidates in enumerate(option["images"]):
            last_error = None
            for url in candidates:
                try:
                    response = session.get(url, timeout=30)
                    response.raise_for_status()
                    data = response.content
                    mime = response.headers.get("content-type", "").split(";", 1)[0].lower()
                    if mime not in EXTENSIONS:
                        raise DownloadError("地址没有返回有效图片")
                    with Image.open(io.BytesIO(data)) as picture:
                        sizes[index] = picture.size
                    cache[index] = (data, mime)
                    break
                except (requests.RequestException, OSError, DownloadError) as error:
                    last_error = error
            else:
                raise DownloadError(f"第 {index + 1} 张图片无法预览：{last_error}")
    option["image_cache"] = cache
    option["image_sizes"] = sizes
