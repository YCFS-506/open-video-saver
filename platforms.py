"""Recognize supported public work links before loading a platform adapter."""

from __future__ import annotations

import re
from urllib.parse import urlparse


SHARE_URL = re.compile(r"https?://[^\s<>\"']+", re.IGNORECASE)
DOUYIN_HOSTS = {"v.douyin.com", "www.douyin.com", "m.douyin.com", "www.iesdouyin.com"}
BILIBILI_HOSTS = {"bilibili.com", "www.bilibili.com", "m.bilibili.com", "t.bilibili.com", "b23.tv", "bili2233.cn"}
XIAOHONGSHU_HOSTS = {"xiaohongshu.com", "www.xiaohongshu.com", "xhslink.com", "www.xhslink.com"}
KUAISHOU_HOSTS = {"kuaishou.com", "www.kuaishou.com", "v.kuaishou.com"}


class DownloadError(RuntimeError):
    pass


def extract_url(text: str) -> str:
    match = SHARE_URL.search(text)
    if not match:
        raise DownloadError("没有找到作品分享链接")
    url = match.group(0).rstrip(".,;:!?，。；：！？")
    host = (urlparse(url).hostname or "").lower()
    if host not in DOUYIN_HOSTS | BILIBILI_HOSTS | XIAOHONGSHU_HOSTS | KUAISHOU_HOSTS:
        raise DownloadError("目前支持抖音、哔哩哔哩、小红书和快手的分享链接")
    return url


def detect_platform(text: str) -> str:
    host = (urlparse(extract_url(text)).hostname or "").lower()
    if host in DOUYIN_HOSTS:
        return "douyin"
    if host in BILIBILI_HOSTS:
        return "bilibili"
    return "xiaohongshu" if host in XIAOHONGSHU_HOSTS else "kuaishou"


def platform_name(platform: str) -> str:
    return {"douyin": "抖音", "bilibili": "哔哩哔哩", "xiaohongshu": "小红书", "kuaishou": "快手"}[platform]
