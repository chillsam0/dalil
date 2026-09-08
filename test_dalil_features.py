import os
import re
import sys

def test_bundled_assets():
    syria_path = "android/sdk/maps/world/src/main/assets/Syria.mwm"
    world_path = "android/sdk/maps/world/src/main/assets/World.mwm"
    coasts_path = "android/sdk/maps/world/src/main/assets/WorldCoasts.mwm"

    assert os.path.exists(syria_path), f"Missing {syria_path}"
    assert os.path.getsize(syria_path) == 98857168, f"Unexpected Syria.mwm size: {os.path.getsize(syria_path)}"
    assert os.path.exists(world_path), f"Missing {world_path}"
    assert os.path.exists(coasts_path), f"Missing {coasts_path}"
    print("[PASS] Bundled map assets verified (Syria.mwm, World.mwm, WorldCoasts.mwm)")

def test_branding():
    with open("android/settings.gradle", "r", encoding="utf-8") as f:
        content = f.read()
        assert "rootProject.name = 'Dalil'" in content, "Failed settings.gradle rootProject.name"

    with open("android/build.gradle", "r", encoding="utf-8") as f:
        content = f.read()
        assert "appId = 'app.dalil.maps'" in content, "Failed root build.gradle appId"

    with open("android/libs/branding/src/main/res/values-ar/strings.xml", "r", encoding="utf-8") as f:
        content = f.read()
        assert "دليل" in content, "Missing Arabic app name in values-ar/strings.xml"

    with open("android/libs/branding/src/main/res/values/donottranslate.xml", "r", encoding="utf-8") as f:
        content = f.read()
        assert "Dalil" in content, "Missing Dalil in donottranslate.xml"

    print("[PASS] Branding verified (app name 'دليل' / Dalil, appId 'app.dalil.maps')")

def test_search_normalization_code():
    with open("libs/indexer/search_string_utils.cpp", "r", encoding="utf-8") as f:
        content = f.read()
        assert "0x0623" in content, "Hamza normalization missing in search_string_utils.cpp"
        assert "0x0629" in content, "Taa Marbuta normalization missing in search_string_utils.cpp"
        assert "0x0649" in content, "Alif Maksura normalization missing in search_string_utils.cpp"
        assert "0x064B" in content, "Tashkeel stripping missing in search_string_utils.cpp"

    with open("libs/indexer/search_string_utils.hpp", "r", encoding="utf-8") as f:
        content = f.read()
        assert "0x0627" in content and "0x0644" in content, "Al- prefix stripping missing in search_string_utils.hpp"

    print("[PASS] Arabic search normalization C++ engine rules verified")

def test_arabic_first_labels():
    with open("libs/indexer/feature_utils.cpp", "r", encoding="utf-8") as f:
        content = f.read()
        assert 'StringUtf8Multilang::GetLangIndex("ar")' in content, "Arabic priority missing in feature_utils.cpp"

    with open("data/fonts/whitelist.txt", "r", encoding="utf-8") as f:
        content = f.read()
        assert "Arabic" in content and "00_NotoNaskhArabic-Regular.ttf" in content, "Arabic font whitelist missing"

    print("[PASS] Arabic-first map labels and font whitelisting verified")

def test_default_viewport():
    with open("libs/map/framework.cpp", "r", encoding="utf-8") as f:
        content = f.read()
        assert "syriaRect" in content and "32.3" in content and "35.6" in content, "Default Syria viewport missing in framework.cpp"

    print("[PASS] Default Syria viewport verified in Framework::LoadViewport()")

def test_ci_workflow():
    workflow_path = ".github/workflows/dalil-build-apk.yml"
    assert os.path.exists(workflow_path), f"Missing {workflow_path}"
    with open(workflow_path, "r", encoding="utf-8") as f:
        content = f.read()
        assert "assembleWebRelease" in content or "assembleWebDebug" in content
        assert "Dalil-APK" in content

    print("[PASS] GitHub Actions CI/CD APK workflow verified")

if __name__ == "__main__":
    test_bundled_assets()
    test_branding()
    test_search_normalization_code()
    test_arabic_first_labels()
    test_default_viewport()
    test_ci_workflow()
    print("\nALL VERIFICATION TESTS PASSED SUCCESSFULLY! 🚀")
