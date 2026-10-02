#include <jni.h>
#include <android/bitmap.h>
#include <opencv2/opencv.hpp>
#include "edge_detector.h"
#include "perspective_corrector.h"
#include "curve_dewarper.h"
#include "burst_fusion.h"
#include "vulkan_compute_engine.h"
#include <android/log.h>

#define TAG "HachiCam-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGI("HachiCam Native Engine loaded! Probing Vulkan GPU compute...");
    bool vulkanReady = VulkanComputeEngine::getInstance().isSupported();
    LOGI("Vulkan Compute Engine ready status: %s", vulkanReady ? "YES (Adreno/Vulkan active)" : "NO (CPU fallback)");
    return JNI_VERSION_1_6;
}

JNIEXPORT jfloatArray JNICALL
Java_com_scanner_app_engine_NativeEdgeDetector_nativeDetectDocument(JNIEnv* env, jobject /* this */, jlong matAddr, jboolean curvedMode, jfloat touchX, jfloat touchY) {
    cv::Mat* grayFrame = reinterpret_cast<cv::Mat*>(matAddr);
    if (!grayFrame) return env->NewFloatArray(0);

    EdgeDetector detector;
    DetectionResult result = detector.detectDocument(*grayFrame, curvedMode, touchX, touchY);

    std::vector<float> outData;
    outData.push_back(result.found ? 1.0f : 0.0f);
    outData.push_back(result.isCurved ? 1.0f : 0.0f);

    if (!result.isCurved) {
        outData.push_back(static_cast<float>(result.corners.size()));
        for (const auto& pt : result.corners) {
            outData.push_back(pt.x);
            outData.push_back(pt.y);
        }
    } else {
        outData.push_back(static_cast<float>(result.boundaryPoints.size()));
        for (const auto& pt : result.boundaryPoints) {
            outData.push_back(pt.x);
            outData.push_back(pt.y);
        }
    }

    jfloatArray retArray = env->NewFloatArray(outData.size());
    env->SetFloatArrayRegion(retArray, 0, outData.size(), outData.data());
    return retArray;
}

JNIEXPORT jlong JNICALL
Java_com_scanner_app_engine_NativePerspective_nativeCorrectPerspective(JNIEnv* env, jobject /* this */, jlong srcMatAddr, jfloatArray corners, jfloat targetAspectRatio) {
    cv::Mat* src = reinterpret_cast<cv::Mat*>(srcMatAddr);
    if (!src) return 0;

    jsize len = env->GetArrayLength(corners);
    std::vector<cv::Point2f> srcCorners;
    jfloat* cornersData = env->GetFloatArrayElements(corners, nullptr);
    
    for (int i = 0; i < len; i += 2) {
        srcCorners.push_back(cv::Point2f(cornersData[i], cornersData[i + 1]));
    }
    env->ReleaseFloatArrayElements(corners, cornersData, JNI_ABORT);

    PerspectiveCorrector corrector;
    cv::Mat warped = corrector.correctPerspective(*src, srcCorners, static_cast<float>(targetAspectRatio));
    
    cv::Mat* result = new cv::Mat(warped);
    return reinterpret_cast<jlong>(result);
}

JNIEXPORT jlong JNICALL
Java_com_scanner_app_engine_NativePerspective_nativeProcessDocument(JNIEnv* env, jobject /* this */, jlong srcMatAddr, jfloatArray corners, jint filterType, jfloat targetAspectRatio) {
    cv::Mat* src = reinterpret_cast<cv::Mat*>(srcMatAddr);
    if (!src) return 0;

    jsize len = env->GetArrayLength(corners);
    std::vector<cv::Point2f> srcCorners;
    jfloat* cornersData = env->GetFloatArrayElements(corners, nullptr);

    for (int i = 0; i < len; i += 2) {
        srcCorners.push_back(cv::Point2f(cornersData[i], cornersData[i + 1]));
    }
    env->ReleaseFloatArrayElements(corners, cornersData, JNI_ABORT);

    PerspectiveCorrector corrector;
    cv::Mat processed = corrector.processDocument(*src, srcCorners, static_cast<FilterType>(filterType), static_cast<float>(targetAspectRatio));

    cv::Mat* result = new cv::Mat(processed);
    return reinterpret_cast<jlong>(result);
}

JNIEXPORT jlong JNICALL
Java_com_scanner_app_engine_NativePerspective_nativeApplyFilter(JNIEnv* /* env */, jobject /* this */, jlong srcMatAddr, jint filterType) {
    cv::Mat* src = reinterpret_cast<cv::Mat*>(srcMatAddr);
    if (!src) return 0;

    PerspectiveCorrector corrector;
    cv::Mat out;
    switch (static_cast<FilterType>(filterType)) {
        case FilterType::MAGIC_COLOR:
            out = corrector.enhanceMagicColor(*src);
            break;
        case FilterType::BW_SAUVOLA:
            out = corrector.enhanceSauvola(*src);
            break;
        case FilterType::GRAYSCALE:
            out = corrector.enhanceGrayscale(*src);
            break;
        case FilterType::ORIGINAL:
        default:
            out = src->clone();
            break;
    }

    cv::Mat* result = new cv::Mat(out);
    return reinterpret_cast<jlong>(result);
}

JNIEXPORT jlong JNICALL
Java_com_scanner_app_engine_NativeBurstFusion_nativeFuseBurstFrames(JNIEnv* env, jobject /* this */, jlongArray matAddrs, jboolean removeGlare, jboolean isScreenMode, jboolean superResolution) {
    if (!matAddrs) return 0;

    jsize len = env->GetArrayLength(matAddrs);
    if (len == 0) return 0;

    jlong* addrs = env->GetLongArrayElements(matAddrs, nullptr);
    std::vector<cv::Mat> frames;
    frames.reserve(len);

    for (int i = 0; i < len; ++i) {
        cv::Mat* pMat = reinterpret_cast<cv::Mat*>(addrs[i]);
        if (pMat && !pMat->empty()) {
            frames.push_back(*pMat);
        }
    }
    env->ReleaseLongArrayElements(matAddrs, addrs, JNI_ABORT);

    if (frames.empty()) return 0;

    BurstFusionEngine engine;
    cv::Mat fused = engine.fuseBurstFrames(frames, removeGlare, isScreenMode, superResolution);

    cv::Mat* result = new cv::Mat(fused);
    return reinterpret_cast<jlong>(result);
}

JNIEXPORT jlong JNICALL
Java_com_scanner_app_engine_NativeCurveDewarper_nativeDewarpCurved(JNIEnv* env, jobject /* this */, jlong srcMatAddr, jfloatArray topPts, jfloatArray bottomPts, jfloatArray leftPts, jfloatArray rightPts) {
    cv::Mat* src = reinterpret_cast<cv::Mat*>(srcMatAddr);
    if (!src) return 0;

    auto getPoints = [&](jfloatArray arr) {
        std::vector<cv::Point2f> pts;
        jsize len = env->GetArrayLength(arr);
        if (len > 0) {
            jfloat* data = env->GetFloatArrayElements(arr, nullptr);
            for (int i = 0; i < len; i += 2) {
                pts.push_back(cv::Point2f(data[i], data[i + 1]));
            }
            env->ReleaseFloatArrayElements(arr, data, JNI_ABORT);
        }
        return pts;
    };

    std::vector<cv::Point2f> top = getPoints(topPts);
    std::vector<cv::Point2f> bottom = getPoints(bottomPts);
    std::vector<cv::Point2f> left = getPoints(leftPts);
    std::vector<cv::Point2f> right = getPoints(rightPts);

    CurveDewarper dewarper;
    cv::Mat dewarped = dewarper.dewarpCurved(*src, top, bottom, left, right);

    cv::Mat* result = new cv::Mat(dewarped);
    return reinterpret_cast<jlong>(result);
}

JNIEXPORT jfloatArray JNICALL
Java_com_scanner_app_engine_NativeEdgeDetector_nativeFindSnapPoint(JNIEnv* env, jobject /* this */, jlong edgeMatAddr, jfloat touchX, jfloat touchY, jfloat radius) {
    cv::Mat* edges = reinterpret_cast<cv::Mat*>(edgeMatAddr);
    if (!edges) return env->NewFloatArray(0);

    EdgeDetector detector;
    std::vector<cv::Point> pts = detector.findMagneticSnapPoint(*edges, cv::Point2f(touchX, touchY), radius);

    std::vector<float> outData;
    if (!pts.empty()) {
        outData.push_back(1.0f); // found
        outData.push_back(static_cast<float>(pts[0].x));
        outData.push_back(static_cast<float>(pts[0].y));
    } else {
        outData.push_back(0.0f); // not found
        outData.push_back(0.0f);
        outData.push_back(0.0f);
    }

    jfloatArray retArray = env->NewFloatArray(outData.size());
    env->SetFloatArrayRegion(retArray, 0, outData.size(), outData.data());
    return retArray;
}

JNIEXPORT jfloat JNICALL
Java_com_scanner_app_engine_NativeEdgeDetector_nativeFindLineOffset(
    JNIEnv* /* env */, jobject /* this */,
    jlong edgeMatAddr,
    jfloat p1x, jfloat p1y,
    jfloat p2x, jfloat p2y,
    jfloat maxOffset
) {
    cv::Mat* edgeMat = reinterpret_cast<cv::Mat*>(edgeMatAddr);
    if (!edgeMat || edgeMat->empty()) {
        return std::numeric_limits<float>::quiet_NaN();
    }

    EdgeDetector detector;
    return detector.findMagneticLineOffset(*edgeMat, cv::Point2f(p1x, p1y), cv::Point2f(p2x, p2y), maxOffset);
}

JNIEXPORT jfloatArray JNICALL
Java_com_scanner_app_engine_NativeEdgeDetector_nativeFindContourAtPoint(
    JNIEnv* env, jobject /* this */,
    jlong grayMatAddr,
    jfloat touchX, jfloat touchY
) {
    cv::Mat* grayMat = reinterpret_cast<cv::Mat*>(grayMatAddr);
    if (!grayMat || grayMat->empty()) {
        std::vector<float> emptyData = {0.0f};
        jfloatArray retArray = env->NewFloatArray(1);
        env->SetFloatArrayRegion(retArray, 0, 1, emptyData.data());
        return retArray;
    }

    EdgeDetector detector;
    std::vector<cv::Point2f> corners = detector.findContourAtPoint(*grayMat, touchX, touchY);

    if (corners.size() == 4) {
        std::vector<float> outData(9);
        outData[0] = 1.0f; // found
        for (int i = 0; i < 4; ++i) {
            outData[1 + 2 * i] = corners[i].x;
            outData[2 + 2 * i] = corners[i].y;
        }
        jfloatArray retArray = env->NewFloatArray(9);
        env->SetFloatArrayRegion(retArray, 0, 9, outData.data());
        return retArray;
    } else {
        std::vector<float> emptyData = {0.0f};
        jfloatArray retArray = env->NewFloatArray(1);
        env->SetFloatArrayRegion(retArray, 0, 1, emptyData.data());
        return retArray;
    }
}

JNIEXPORT jobjectArray JNICALL
Java_com_scanner_app_engine_NativeEdgeDetector_nativeDetectStructuralLines(
    JNIEnv* env, jobject /* this */,
    jobject bitmap,
    jfloat origWidth,
    jfloat origHeight
) {
    jclass floatArrayClass = env->FindClass("[F");
    if (!bitmap) {
        jobjectArray result = env->NewObjectArray(2, floatArrayClass, nullptr);
        jfloatArray emptyH = env->NewFloatArray(0);
        jfloatArray emptyV = env->NewFloatArray(0);
        env->SetObjectArrayElement(result, 0, emptyH);
        env->SetObjectArrayElement(result, 1, emptyV);
        return result;
    }

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) {
        jobjectArray result = env->NewObjectArray(2, floatArrayClass, nullptr);
        jfloatArray emptyH = env->NewFloatArray(0);
        jfloatArray emptyV = env->NewFloatArray(0);
        env->SetObjectArrayElement(result, 0, emptyH);
        env->SetObjectArrayElement(result, 1, emptyV);
        return result;
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0 || !pixels) {
        jobjectArray result = env->NewObjectArray(2, floatArrayClass, nullptr);
        jfloatArray emptyH = env->NewFloatArray(0);
        jfloatArray emptyV = env->NewFloatArray(0);
        env->SetObjectArrayElement(result, 0, emptyH);
        env->SetObjectArrayElement(result, 1, emptyV);
        return result;
    }

    cv::Mat gray;
    if (info.format == ANDROID_BITMAP_FORMAT_RGBA_8888) {
        cv::Mat rgba(info.height, info.width, CV_8UC4, pixels, info.stride);
        cv::cvtColor(rgba, gray, cv::COLOR_RGBA2GRAY);
    } else if (info.format == ANDROID_BITMAP_FORMAT_RGB_565) {
        cv::Mat rgb565(info.height, info.width, CV_8UC2, pixels, info.stride);
        cv::cvtColor(rgb565, gray, cv::COLOR_BGR5652GRAY);
    } else if (info.format == ANDROID_BITMAP_FORMAT_A_8) {
        cv::Mat a8(info.height, info.width, CV_8UC1, pixels, info.stride);
        gray = a8.clone();
    }

    AndroidBitmap_unlockPixels(env, bitmap);

    if (gray.empty()) {
        jobjectArray result = env->NewObjectArray(2, floatArrayClass, nullptr);
        jfloatArray emptyH = env->NewFloatArray(0);
        jfloatArray emptyV = env->NewFloatArray(0);
        env->SetObjectArrayElement(result, 0, emptyH);
        env->SetObjectArrayElement(result, 1, emptyV);
        return result;
    }

    float effectiveW = (origWidth > 0.0f) ? origWidth : static_cast<float>(info.width);
    float effectiveH = (origHeight > 0.0f) ? origHeight : static_cast<float>(info.height);

    EdgeDetector detector;
    EdgeDetector::StructuralLinesResult linesResult = detector.detectStructuralLines(
        gray,
        effectiveW,
        effectiveH
    );

    jobjectArray result = env->NewObjectArray(2, floatArrayClass, nullptr);

    jfloatArray hArray = env->NewFloatArray(linesResult.horizontalLines.size());
    if (!linesResult.horizontalLines.empty()) {
        env->SetFloatArrayRegion(hArray, 0, linesResult.horizontalLines.size(), linesResult.horizontalLines.data());
    }
    env->SetObjectArrayElement(result, 0, hArray);

    jfloatArray vArray = env->NewFloatArray(linesResult.verticalLines.size());
    if (!linesResult.verticalLines.empty()) {
        env->SetFloatArrayRegion(vArray, 0, linesResult.verticalLines.size(), linesResult.verticalLines.data());
    }
    env->SetObjectArrayElement(result, 1, vArray);

    return result;
}

} // extern "C"


