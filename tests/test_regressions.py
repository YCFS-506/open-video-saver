"""Offline regressions for link routing and selective image saving."""

import io
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock

from PIL import Image

from downloader import resolve_work
from image_posts import save_gallery
from platforms import DownloadError, detect_platform, extract_url


class LinkRoutingTests(unittest.TestCase):
    def test_share_text_and_supported_platforms(self):
        cases = {
            "https://v.douyin.com/example/": "douyin",
            "https://www.bilibili.com/video/BV1hka76JEp1/": "bilibili",
            "https://t.bilibili.com/1141239782005800981": "bilibili",
            "https://www.xiaohongshu.com/explore/6a721c6800000000220145bb": "xiaohongshu",
            "https://www.kuaishou.com/f/example": "kuaishou",
        }
        for url, platform in cases.items():
            with self.subTest(platform=platform):
                text = f"复制链接查看作品 {url}，分享文案"
                # Real share text separates its URL with whitespace.
                text = text.replace("，分享文案", "， 分享文案")
                self.assertEqual(extract_url(text), url)
                self.assertEqual(detect_platform(text), platform)

    def test_lookalike_domain_is_not_a_supported_platform(self):
        with self.assertRaises(DownloadError):
            extract_url("https://www.douyin.com.example.invalid/video/123")

    def test_legacy_image_shortlink_is_not_a_video(self):
        context = Mock()
        response = context.request.get.return_value
        response.status = 302
        response.headers = {
            "location": "https://www.iesdouyin.com/share/video/7419297316135243042/?schema_type=37"
        }
        response.text.return_value = ""
        self.assertEqual(resolve_work(context, "https://v.douyin.com/example/"),
                         ("images", "7419297316135243042"))

    def test_regular_video_shortlink_keeps_video_type(self):
        context = Mock()
        response = context.request.get.return_value
        response.status = 302
        response.headers = {
            "location": "https://www.iesdouyin.com/share/video/7690083874138836264/"
        }
        response.text.return_value = ""
        self.assertEqual(resolve_work(context, "https://v.douyin.com/example/"),
                         ("video", "7690083874138836264"))


class GallerySavingTests(unittest.TestCase):
    def test_selected_images_keep_original_numbers_and_bytes(self):
        pictures = {}
        for index, color in enumerate(("red", "green", "blue")):
            stream = io.BytesIO()
            Image.new("RGB", (128, 96), color).save(stream, "PNG")
            pictures[index] = (stream.getvalue(), "image/png")
        fetch = Mock(side_effect=AssertionError("Cached images must not be fetched again"))
        with tempfile.TemporaryDirectory() as name:
            saved = save_gallery(
                Path(name), "测试", "123", [["https://example.invalid/image"]] * 3,
                "正文", "https://example.invalid/post", fetch, lambda *_: None,
                selected_indices=[2, 0], image_cache=pictures,
            )
            self.assertEqual({path.name for path in saved.iterdir()},
                             {"01.png", "03.png", "文案.txt", "来源.txt"})
            self.assertEqual((saved / "01.png").read_bytes(), pictures[0][0])
            self.assertEqual((saved / "03.png").read_bytes(), pictures[2][0])
            self.assertEqual((saved / "文案.txt").read_text(encoding="utf-8-sig").strip(), "正文")
            self.assertIn("原作品序号：1、3", (saved / "来源.txt").read_text(encoding="utf-8-sig"))
        fetch.assert_not_called()


if __name__ == "__main__":
    unittest.main()
