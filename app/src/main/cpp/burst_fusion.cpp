#include "burst_fusion.h"
#include <opencv2/photo.hpp>
#include <vector>
#include <algorithm>
#include <cmath>
#include <android/log.h>

#define LOG_TAG "HachiCam-Fusion"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ============================================================================
// 第一级：全局单应性粗配准 (ORB + RANSAC Homography)
// SPEC_12 §2.2 (升级为 cv::INTER_CUBIC 双三次插值保留极高频 MTF)
// ============================================================================
bool BurstFusionEngine::alignFrameHomography(const cv::Mat& src, const cv::Mat& ref, cv::Mat& outWarped, cv::Mat& outH) {
    if (src.empty() || ref.empty() || src.size() != ref.size()) {
        return false;
    }

    // 1. 降采样至最长边 960 以获得全局均匀特征且保证计算在数十毫秒内完成
    int maxDim = std::max(ref.cols, ref.rows);
    float scale = (maxDim > 960) ? 960.0f / maxDim : 1.0f;

    cv::Mat graySrc, grayRef;
    if (src.channels() == 3) cv::cvtColor(src, graySrc, cv::COLOR_BGR2GRAY);
    else graySrc = src;

    if (ref.channels() == 3) cv::cvtColor(ref, grayRef, cv::COLOR_BGR2GRAY);
    else grayRef = ref;

    cv::Mat smallSrc, smallRef;
    if (scale < 1.0f) {
        cv::resize(graySrc, smallSrc, cv::Size(), scale, scale, cv::INTER_AREA);
        cv::resize(grayRef, smallRef, cv::Size(), scale, scale, cv::INTER_AREA);
    } else {
        smallSrc = graySrc;
        smallRef = grayRef;
    }

    // 2. ORB 提取关键点
    cv::Ptr<cv::ORB> orb = cv::ORB::create(1200);
    std::vector<cv::KeyPoint> kpSrc, kpRef;
    cv::Mat descSrc, descRef;
    orb->detectAndCompute(smallSrc, cv::noArray(), kpSrc, descSrc);
    orb->detectAndCompute(smallRef, cv::noArray(), kpRef, descRef);

    if (descSrc.empty() || descRef.empty() || kpSrc.size() < 15 || kpRef.size() < 15) {
        LOGW("alignFrameHomography: insufficient keypoints (src=%zu, ref=%zu)", kpSrc.size(), kpRef.size());
        return false;
    }

    // 3. 汉明距离交叉匹配
    cv::BFMatcher matcher(cv::NORM_HAMMING, true);
    std::vector<cv::DMatch> matches;
    matcher.match(descSrc, descRef, matches);

    if (matches.size() < 12) {
        LOGW("alignFrameHomography: insufficient matches (%zu)", matches.size());
        return false;
    }

    std::sort(matches.begin(), matches.end(), [](const cv::DMatch& a, const cv::DMatch& b) {
        return a.distance < b.distance;
    });

    int goodCount = std::min(static_cast<int>(matches.size()), 180);
    std::vector<cv::Point2f> ptsSrc, ptsRef;
    ptsSrc.reserve(goodCount);
    ptsRef.reserve(goodCount);

    float invScale = (scale < 1.0f) ? (1.0f / scale) : 1.0f;
    for (int i = 0; i < goodCount; ++i) {
        ptsSrc.push_back(kpSrc[matches[i].queryIdx].pt * invScale);
        ptsRef.push_back(kpRef[matches[i].trainIdx].pt * invScale);
    }

    // 4. RANSAC 求解单应性变换矩阵并校验内点率
    cv::Mat inlierMask;
    cv::Mat H = cv::findHomography(ptsSrc, ptsRef, cv::RANSAC, 3.0, inlierMask);
    if (H.empty()) {
        LOGW("alignFrameHomography: RANSAC homography estimation failed");
        return false;
    }

    int inlierCount = cv::countNonZero(inlierMask);
    float inlierRatio = (goodCount > 0) ? (static_cast<float>(inlierCount) / static_cast<float>(goodCount)) : 0.0f;
    if (inlierCount < 15 || inlierRatio < 0.10f) {
        LOGW("alignFrameHomography: low inlier ratio (%d / %d = %.2f)", inlierCount, goodCount, inlierRatio);
        return false;
    }

    // 校验单应性矩阵仿射行列式，杜绝畸变奇异矩阵 (det 应在 1.0 附近)
    double det = H.at<double>(0,0) * H.at<double>(1,1) - H.at<double>(0,1) * H.at<double>(1,0);
    if (std::abs(det - 1.0) > 0.40) {
        LOGW("alignFrameHomography: degenerate homography determinant (%.3f)", det);
        return false;
    }

    outH = H;
    cv::warpPerspective(src, outWarped, H, ref.size(), cv::INTER_CUBIC, cv::BORDER_CONSTANT, cv::Scalar(0, 0, 0));
    return true;
}

// ============================================================================
// 高亮过渡区光度自适应增益比估算 (保留供接口兼容)
// ============================================================================
float BurstFusionEngine::estimateHighlightAdaptationGain(
    const cv::Mat& bgrBase,
    const cv::Mat& bgrCand,
    const cv::Mat& validMask) {

    int rows = bgrBase.rows;
    int cols = bgrBase.cols;

    std::vector<float> ratios;
    ratios.reserve(5000);

    int step = std::max(1, static_cast<int>(std::sqrt((rows * cols) / 5000.0f)));
    for (int y = 0; y < rows; y += step) {
        const cv::Vec3b* pB = bgrBase.ptr<cv::Vec3b>(y);
        const cv::Vec3b* pC = bgrCand.ptr<cv::Vec3b>(y);
        const uchar* pV = validMask.ptr<uchar>(y);

        for (int x = 0; x < cols; x += step) {
            if (pV[x] == 0) continue;
            const cv::Vec3b& b0 = pB[x];
            const cv::Vec3b& bk = pC[x];

            float y0 = 0.114f * static_cast<float>(b0[0]) + 0.587f * static_cast<float>(b0[1]) + 0.299f * static_cast<float>(b0[2]);
            float y1 = 0.114f * static_cast<float>(bk[0]) + 0.587f * static_cast<float>(bk[1]) + 0.299f * static_cast<float>(bk[2]);

            if (y0 >= 180.0f && y0 <= 245.0f && y1 >= 15.0f) {
                ratios.push_back(y0 / y1);
            }
        }
    }

    if (ratios.size() < 20) {
        return 4.0f;
    }

    size_t midIdx = ratios.size() / 2;
    std::nth_element(ratios.begin(), ratios.begin() + midIdx, ratios.end());
    float medRatio = ratios[midIdx];
    return std::clamp(medRatio, 1.05f, 20.0f);
}

// ============================================================================
// 9帧多曝光金字塔 + 50MP 亚像素超分融合引擎 (Apple Deep Fusion / Smart HDR 架构)
// ============================================================================
cv::Mat BurstFusionEngine::fuseBurstFrames(
    const std::vector<cv::Mat>& burstFrames,
    bool removeGlare,
    bool isScreenMode,
    bool superResolution
) {
    if (burstFrames.empty()) return cv::Mat();
    if (burstFrames.size() == 1 && !superResolution) return burstFrames[0].clone();

    const cv::Mat& refFrame = burstFrames[0];
    int rows = refFrame.rows;
    int cols = refFrame.cols;
    int nFrames = static_cast<int>(burstFrames.size());

    // ────────────────────────────────────────────────────────────────────────
    // 1. 曝光分层 (Tier Partitioning)
    // ────────────────────────────────────────────────────────────────────────
    std::vector<cv::Mat> tier1; // Tier 1: 基准与超分锚点帧 (EV 0)
    std::vector<cv::Mat> tier2; // Tier 2: 中灰过渡与防溢出帧 (EV -2.5)
    std::vector<cv::Mat> tier3; // Tier 3: 极高光灯丝微细节帧 (EV -5.0)

    if (nFrames == 9) {
        tier1.assign(burstFrames.begin(), burstFrames.begin() + 4);
        tier2.assign(burstFrames.begin() + 4, burstFrames.begin() + 7);
        tier3.assign(burstFrames.begin() + 7, burstFrames.end());
    } else if (nFrames >= 4) {
        // 自适应亮度聚类分层
        std::vector<float> avgLuma(nFrames, 0.0f);
        for (int i = 0; i < nFrames; ++i) {
            int rStep = std::max(1, burstFrames[i].rows / 64);
            int cStep = std::max(1, burstFrames[i].cols / 64);
            double sum = 0.0;
            int count = 0;
            for (int r = 0; r < burstFrames[i].rows; r += rStep) {
                const cv::Vec3b* ptr = burstFrames[i].ptr<cv::Vec3b>(r);
                for (int c = 0; c < burstFrames[i].cols; c += cStep) {
                    sum += 0.114 * ptr[c][0] + 0.587 * ptr[c][1] + 0.299 * ptr[c][2];
                    count++;
                }
            }
            avgLuma[i] = (count > 0) ? static_cast<float>(sum / count) : 0.0f;
        }

        float baseLuma = std::max(avgLuma[0], 1.0f);
        for (int i = 0; i < nFrames; ++i) {
            if (avgLuma[i] >= baseLuma * 0.55f) {
                tier1.push_back(burstFrames[i]);
            } else if (avgLuma[i] >= baseLuma * 0.12f) {
                tier2.push_back(burstFrames[i]);
            } else {
                tier3.push_back(burstFrames[i]);
            }
        }
        if (tier1.empty()) tier1.push_back(burstFrames[0]);
    } else {
        tier1 = burstFrames;
    }

    LOGI("fuseBurstFrames: %d input frames partitioned into Tier 1 (Base): %zu, Tier 2 (Mid): %zu, Tier 3 (Short): %zu",
         nFrames, tier1.size(), tier2.size(), tier3.size());

    // 预计算时域光度差高斯核权重查找表 (避免跨核心跨帧重复 exp 求值)
    static float expLUT[256];
    static bool expLutInit = false;
    if (!expLutInit) {
        for (int i = 0; i < 256; ++i) {
            float d = static_cast<float>(i);
            expLUT[i] = std::exp(-(d * d) / (2.0f * 18.0f * 18.0f));
        }
        expLutInit = true;
    }

    // ────────────────────────────────────────────────────────────────────────
    // 分支 A: 50MP 双尺度多相超分与曝光金字塔融合 (Super-Resolution Mode)
    // ────────────────────────────────────────────────────────────────────────
    if (superResolution) {
        int superRows = rows * 2;
        int superCols = cols * 2;

        cv::Mat S2 = (cv::Mat_<double>(3, 3) <<
            2.0, 0.0, 0.0,
            0.0, 2.0, 0.0,
            0.0, 0.0, 1.0);

        // ── 步骤 1: 4 帧 Tier 1 亚像素多相叠加超分重建 (50MP Base Plate) ──
        cv::Mat baseSuper;
        cv::resize(tier1[0], baseSuper, cv::Size(superCols, superRows), 0, 0, cv::INTER_CUBIC);

        cv::Mat accum(superRows, superCols, CV_32FC3);
        baseSuper.convertTo(accum, CV_32FC3);
        cv::Mat weights(superRows, superCols, CV_32FC1, cv::Scalar(1.0f));
        cv::Mat validMaskSrc = cv::Mat::ones(tier1[0].size(), CV_8UC1) * 255;

        for (size_t k = 1; k < tier1.size(); ++k) {
            cv::Mat warped1x, H;
            if (!alignFrameHomography(tier1[k], tier1[0], warped1x, H)) {
                H = cv::Mat::eye(3, 3, CV_64F);
            }
            cv::Mat H2x = S2 * H;
            cv::Mat candWarpedSuper;
            cv::warpPerspective(tier1[k], candWarpedSuper, H2x, cv::Size(superCols, superRows), cv::INTER_LINEAR, cv::BORDER_CONSTANT, cv::Scalar(0, 0, 0));
            cv::Mat validMaskSuper;
            cv::warpPerspective(validMaskSrc, validMaskSuper, H2x, cv::Size(superCols, superRows), cv::INTER_NEAREST, cv::BORDER_CONSTANT, cv::Scalar(0));

            #pragma omp parallel for schedule(static)
            for (int y = 0; y < superRows; ++y) {
                const cv::Vec3b* pBase = baseSuper.ptr<cv::Vec3b>(y);
                const cv::Vec3b* pCand = candWarpedSuper.ptr<cv::Vec3b>(y);
                const uchar* pValid = validMaskSuper.ptr<uchar>(y);
                cv::Vec3f* pAccum = accum.ptr<cv::Vec3f>(y);
                float* pWeight = weights.ptr<float>(y);

                for (int x = 0; x < superCols; ++x) {
                    if (pValid[x] == 0) continue;
                    const cv::Vec3b& b0 = pBase[x];
                    const cv::Vec3b& bk = pCand[x];

                    int y0 = (29 * b0[0] + 150 * b0[1] + 77 * b0[2]) >> 8;
                    int yk = (29 * bk[0] + 150 * bk[1] + 77 * bk[2]) >> 8;

                    int idiff = std::abs(y0 - yk);
                    if (idiff > 255) idiff = 255;
                    float w = expLUT[idiff];

                    if (w > 0.04f) {
                        pAccum[x][0] += w * static_cast<float>(bk[0]);
                        pAccum[x][1] += w * static_cast<float>(bk[1]);
                        pAccum[x][2] += w * static_cast<float>(bk[2]);
                        pWeight[x] += w;
                    }
                }
            }
        }

        cv::Mat I_base_50M(superRows, superCols, CV_8UC3);
        #pragma omp parallel for schedule(static)
        for (int y = 0; y < superRows; ++y) {
            const cv::Vec3f* pAccum = accum.ptr<cv::Vec3f>(y);
            const float* pWeight = weights.ptr<float>(y);
            cv::Vec3b* pDst = I_base_50M.ptr<cv::Vec3b>(y);
            for (int x = 0; x < superCols; ++x) {
                float invW = 1.0f / std::max(pWeight[x], 0.001f);
                pDst[x][0] = cv::saturate_cast<uchar>(pAccum[x][0] * invW);
                pDst[x][1] = cv::saturate_cast<uchar>(pAccum[x][1] * invW);
                pDst[x][2] = cv::saturate_cast<uchar>(pAccum[x][2] * invW);
            }
        }

        cv::Mat I_base_12M;
        cv::resize(I_base_50M, I_base_12M, cv::Size(cols, rows), 0, 0, cv::INTER_AREA);

        // ── 步骤 2: Tier 2 中灰曝光版生成 (12MP Plate) ──
        cv::Mat I_mid_12M;
        if (!tier2.empty()) {
            cv::Mat H_mid0;
            cv::Mat warpedDummy;
            if (!alignFrameHomography(tier2[0], tier1[0], warpedDummy, H_mid0)) {
                LOGW("Tier 2 Frame 0 alignment to Tier 1 Frame 0 failed; using identity fallback");
                H_mid0 = cv::Mat::eye(3, 3, CV_64F);
            }
            std::vector<cv::Mat> midWarped1xList;
            for (size_t k = 0; k < tier2.size(); ++k) {
                cv::Mat Hk;
                if (k == 0) {
                    Hk = H_mid0;
                } else {
                    cv::Mat H_rel;
                    if (!alignFrameHomography(tier2[k], tier2[0], warpedDummy, H_rel)) {
                        H_rel = cv::Mat::eye(3, 3, CV_64F);
                    }
                    Hk = H_mid0 * H_rel;
                }
                cv::Mat w1x;
                cv::warpPerspective(tier2[k], w1x, Hk, cv::Size(cols, rows), cv::INTER_CUBIC, cv::BORDER_CONSTANT, cv::Scalar(0, 0, 0));
                midWarped1xList.push_back(w1x);
            }

            cv::Mat midAccum(rows, cols, CV_32FC3, cv::Scalar(0, 0, 0));
            cv::Mat midCount(rows, cols, CV_32FC1, cv::Scalar(0));
            for (const auto& w1x : midWarped1xList) {
                #pragma omp parallel for schedule(static)
                for (int y = 0; y < rows; ++y) {
                    const cv::Vec3b* src = w1x.ptr<cv::Vec3b>(y);
                    cv::Vec3f* dst = midAccum.ptr<cv::Vec3f>(y);
                    float* cnt = midCount.ptr<float>(y);
                    for (int x = 0; x < cols; ++x) {
                        if (src[x][0] != 0 || src[x][1] != 0 || src[x][2] != 0) {
                            dst[x][0] += src[x][0];
                            dst[x][1] += src[x][1];
                            dst[x][2] += src[x][2];
                            cnt[x] += 1.0f;
                        }
                    }
                }
            }
            I_mid_12M = cv::Mat(rows, cols, CV_8UC3);
            #pragma omp parallel for schedule(static)
            for (int y = 0; y < rows; ++y) {
                const cv::Vec3f* dst = midAccum.ptr<cv::Vec3f>(y);
                const float* cnt = midCount.ptr<float>(y);
                cv::Vec3b* out = I_mid_12M.ptr<cv::Vec3b>(y);
                for (int x = 0; x < cols; ++x) {
                    float c = std::max(cnt[x], 1.0f);
                    out[x][0] = cv::saturate_cast<uchar>(dst[x][0] / c);
                    out[x][1] = cv::saturate_cast<uchar>(dst[x][1] / c);
                    out[x][2] = cv::saturate_cast<uchar>(dst[x][2] / c);
                }
            }
        }

        // ── 步骤 3: Tier 3 极高光曝光版生成 (12MP Plate + 50MP 灯丝微细节) ──
        cv::Mat I_short_12M;
        cv::Mat shortSuper;
        if (!tier3.empty()) {
            cv::Mat H_short0;
            cv::Mat warpedDummy;
            bool aligned = false;
            if (!tier2.empty() && alignFrameHomography(tier3[0], tier2[0], warpedDummy, H_short0)) {
                cv::Mat H_mid0;
                if (alignFrameHomography(tier2[0], tier1[0], warpedDummy, H_mid0)) {
                    H_short0 = H_mid0 * H_short0;
                    aligned = true;
                }
            }
            if (!aligned && alignFrameHomography(tier3[0], tier1[0], warpedDummy, H_short0)) {
                aligned = true;
            }
            if (!aligned) {
                LOGW("Tier 3 Frame 0 alignment failed in dark scene; adopting identity fallback H=I");
                H_short0 = cv::Mat::eye(3, 3, CV_64F);
            }

            std::vector<cv::Mat> shortWarped1xList;
            for (size_t k = 0; k < tier3.size(); ++k) {
                cv::Mat Hk;
                if (k == 0) {
                    Hk = H_short0;
                } else {
                    cv::Mat H_rel;
                    if (!alignFrameHomography(tier3[k], tier3[0], warpedDummy, H_rel)) {
                        H_rel = cv::Mat::eye(3, 3, CV_64F);
                    }
                    Hk = H_short0 * H_rel;
                }
                cv::Mat w1x;
                cv::warpPerspective(tier3[k], w1x, Hk, cv::Size(cols, rows), cv::INTER_CUBIC, cv::BORDER_CONSTANT, cv::Scalar(0, 0, 0));
                shortWarped1xList.push_back(w1x);
            }

            cv::Mat shortAccum(rows, cols, CV_32FC3, cv::Scalar(0, 0, 0));
            cv::Mat shortCount(rows, cols, CV_32FC1, cv::Scalar(0));
            for (const auto& w1x : shortWarped1xList) {
                #pragma omp parallel for schedule(static)
                for (int y = 0; y < rows; ++y) {
                    const cv::Vec3b* src = w1x.ptr<cv::Vec3b>(y);
                    cv::Vec3f* dst = shortAccum.ptr<cv::Vec3f>(y);
                    float* cnt = shortCount.ptr<float>(y);
                    for (int x = 0; x < cols; ++x) {
                        if (src[x][0] != 0 || src[x][1] != 0 || src[x][2] != 0) {
                            dst[x][0] += src[x][0];
                            dst[x][1] += src[x][1];
                            dst[x][2] += src[x][2];
                            cnt[x] += 1.0f;
                        }
                    }
                }
            }
            I_short_12M = cv::Mat(rows, cols, CV_8UC3);
            #pragma omp parallel for schedule(static)
            for (int y = 0; y < rows; ++y) {
                const cv::Vec3f* dst = shortAccum.ptr<cv::Vec3f>(y);
                const float* cnt = shortCount.ptr<float>(y);
                cv::Vec3b* out = I_short_12M.ptr<cv::Vec3b>(y);
                for (int x = 0; x < cols; ++x) {
                    float c = std::max(cnt[x], 1.0f);
                    out[x][0] = cv::saturate_cast<uchar>(dst[x][0] / c);
                    out[x][1] = cv::saturate_cast<uchar>(dst[x][1] / c);
                    out[x][2] = cv::saturate_cast<uchar>(dst[x][2] / c);
                }
            }

            cv::Mat H2x_short = S2 * H_short0;
            cv::warpPerspective(tier3[0], shortSuper, H2x_short, cv::Size(superCols, superRows), cv::INTER_CUBIC, cv::BORDER_CONSTANT, cv::Scalar(0, 0, 0));
        }

        // ── 步骤 4: OpenCV MergeMertens 多尺度拉普拉斯金字塔曝光融合 ──
        std::vector<cv::Mat> plates1x;
        plates1x.push_back(I_base_12M);
        if (!I_mid_12M.empty()) plates1x.push_back(I_mid_12M);
        if (!I_short_12M.empty()) plates1x.push_back(I_short_12M);

        cv::Mat superResult;
        if (plates1x.size() >= 2) {
            LOGI("Running OpenCV createMergeMertens multi-scale pyramid fusion on %zu exposure plates...", plates1x.size());
            cv::Ptr<cv::MergeMertens> merger = cv::createMergeMertens(1.0f, 1.0f, 1.0f);
            cv::Mat fused1xFloat;
            merger->process(plates1x, fused1xFloat);
            cv::Mat fused1x;
            fused1xFloat.convertTo(fused1x, CV_8UC3, 255.0);

            // 双三次平滑升采样到 50MP
            cv::Mat fused50M_smooth;
            cv::resize(fused1x, fused50M_smooth, cv::Size(superCols, superRows), 0, 0, cv::INTER_CUBIC);

            cv::Mat baseSmooth50M;
            cv::resize(I_base_12M, baseSmooth50M, cv::Size(superCols, superRows), 0, 0, cv::INTER_CUBIC);

            cv::Mat shortSmooth50M;
            bool hasShortDetail = !shortSuper.empty();
            if (hasShortDetail) {
                cv::resize(I_short_12M, shortSmooth50M, cv::Size(superCols, superRows), 0, 0, cv::INTER_CUBIC);
            }

            // 双尺度高频超分纹理回填 (Frequency Split High-Frequency Detail Transfer)
            // 在中暗部 (Y <= 180): 100% 注入 4 帧亚像素超分高频纹理 D_base
            // 在极高光部 (Y > 180): 平滑过渡注入短曝光灯丝与高光文字高频纹理 D_short
            superResult = cv::Mat(superRows, superCols, CV_8UC3);
            #pragma omp parallel for schedule(static)
            for (int y = 0; y < superRows; ++y) {
                const cv::Vec3b* pFusedSmooth = fused50M_smooth.ptr<cv::Vec3b>(y);
                const cv::Vec3b* pBaseSuper = I_base_50M.ptr<cv::Vec3b>(y);
                const cv::Vec3b* pBaseSmooth = baseSmooth50M.ptr<cv::Vec3b>(y);
                const cv::Vec3b* pShortSuper = hasShortDetail ? shortSuper.ptr<cv::Vec3b>(y) : nullptr;
                const cv::Vec3b* pShortSmooth = hasShortDetail ? shortSmooth50M.ptr<cv::Vec3b>(y) : nullptr;
                cv::Vec3b* pOut = superResult.ptr<cv::Vec3b>(y);

                for (int x = 0; x < superCols; ++x) {
                    const cv::Vec3b& f = pFusedSmooth[x];
                    float yHdr = 0.114f * static_cast<float>(f[0]) + 0.587f * static_cast<float>(f[1]) + 0.299f * static_cast<float>(f[2]);
                    float alphaHighlight = std::clamp((yHdr - 180.0f) / 60.0f, 0.0f, 1.0f);

                    for (int c = 0; c < 3; ++c) {
                        float dBase = static_cast<float>(pBaseSuper[x][c]) - static_cast<float>(pBaseSmooth[x][c]);
                        float dShort = 0.0f;
                        if (hasShortDetail) {
                            dShort = static_cast<float>(pShortSuper[x][c]) - static_cast<float>(pShortSmooth[x][c]);
                        }
                        float detail = (1.0f - alphaHighlight) * dBase + alphaHighlight * dShort;
                        pOut[x][c] = cv::saturate_cast<uchar>(static_cast<float>(f[c]) + detail);
                    }
                }
            }
            LOGI("50MP Two-Scale Frequency Super-Resolution HDR Fusion completed successfully!");
        } else {
            superResult = I_base_50M;
        }

        // ── 步骤 5: 电影级 S-Curve 暗部黑电平压制 (SPEC_13 §2.2) ──
        #pragma omp parallel for schedule(static)
        for (int y = 0; y < superRows; ++y) {
            cv::Vec3b* pDst = superResult.ptr<cv::Vec3b>(y);
            for (int x = 0; x < superCols; ++x) {
                cv::Vec3b& px = pDst[x];
                float Y = 0.114f * static_cast<float>(px[0]) + 0.587f * static_cast<float>(px[1]) + 0.299f * static_cast<float>(px[2]);
                if (Y <= 28.0f) {
                    float u = Y / 28.0f;
                    float Y_tone = Y * std::pow(u, 0.65f);
                    float scale = Y_tone / std::max(Y, 0.001f);
                    px[0] = cv::saturate_cast<uchar>(static_cast<float>(px[0]) * scale);
                    px[1] = cv::saturate_cast<uchar>(static_cast<float>(px[1]) * scale);
                    px[2] = cv::saturate_cast<uchar>(static_cast<float>(px[2]) * scale);
                }
            }
        }

        // ── 步骤 6: 自适应保边微反差质感合成 (SPEC_13 §2.3) ──
        cv::Mat Y_mat(superRows, superCols, CV_8UC1);
        #pragma omp parallel for schedule(static)
        for (int y = 0; y < superRows; ++y) {
            const cv::Vec3b* pDst = superResult.ptr<cv::Vec3b>(y);
            uchar* pY = Y_mat.ptr<uchar>(y);
            for (int x = 0; x < superCols; ++x) {
                const cv::Vec3b& px = pDst[x];
                pY[x] = static_cast<uchar>((29 * px[0] + 150 * px[1] + 77 * px[2]) >> 8);
            }
        }

        cv::Mat Y_blur;
        cv::GaussianBlur(Y_mat, Y_blur, cv::Size(5, 5), 1.5);

        const int tau = 2;
        const float beta = 0.55f;

        #pragma omp parallel for schedule(static)
        for (int y = 0; y < superRows; ++y) {
            const uchar* pY = Y_mat.ptr<uchar>(y);
            const uchar* pYBlur = Y_blur.ptr<uchar>(y);
            cv::Vec3b* pDst = superResult.ptr<cv::Vec3b>(y);

            for (int x = 0; x < superCols; ++x) {
                int D = static_cast<int>(pY[x]) - static_cast<int>(pYBlur[x]);
                int absD = std::abs(D);
                if (absD > tau) {
                    float sign = (D > 0) ? 1.0f : -1.0f;
                    float deltaY = sign * std::min(static_cast<float>(absD - tau) * beta, 16.0f);
                    cv::Vec3b& px = pDst[x];
                    px[0] = cv::saturate_cast<uchar>(static_cast<float>(px[0]) + deltaY);
                    px[1] = cv::saturate_cast<uchar>(static_cast<float>(px[1]) + deltaY);
                    px[2] = cv::saturate_cast<uchar>(static_cast<float>(px[2]) + deltaY);
                }
            }
        }

        return superResult;
    }

    // ────────────────────────────────────────────────────────────────────────
    // 分支 B: 12MP 原生多曝光金字塔融合 (Native 1x Mode)
    // ────────────────────────────────────────────────────────────────────────
    cv::Mat I_base_12M = tier1[0].clone();
    cv::Mat I_mid_12M;
    if (!tier2.empty()) {
        cv::Mat H_mid0, dummy;
        if (!alignFrameHomography(tier2[0], tier1[0], dummy, H_mid0)) {
            H_mid0 = cv::Mat::eye(3, 3, CV_64F);
        }
        cv::warpPerspective(tier2[0], I_mid_12M, H_mid0, cv::Size(cols, rows), cv::INTER_CUBIC, cv::BORDER_CONSTANT, cv::Scalar(0, 0, 0));
    }

    cv::Mat I_short_12M;
    if (!tier3.empty()) {
        cv::Mat H_short0, dummy;
        if (!alignFrameHomography(tier3[0], tier1[0], dummy, H_short0)) {
            H_short0 = cv::Mat::eye(3, 3, CV_64F);
        }
        cv::warpPerspective(tier3[0], I_short_12M, H_short0, cv::Size(cols, rows), cv::INTER_CUBIC, cv::BORDER_CONSTANT, cv::Scalar(0, 0, 0));
    }

    std::vector<cv::Mat> plates;
    plates.push_back(I_base_12M);
    if (!I_mid_12M.empty()) plates.push_back(I_mid_12M);
    if (!I_short_12M.empty()) plates.push_back(I_short_12M);

    cv::Mat result1x;
    if (plates.size() >= 2) {
        cv::Ptr<cv::MergeMertens> merger = cv::createMergeMertens(1.0f, 1.0f, 1.0f);
        cv::Mat fusedFloat;
        merger->process(plates, fusedFloat);
        fusedFloat.convertTo(result1x, CV_8UC3, 255.0);
    } else {
        result1x = I_base_12M;
    }

    // S-curve toe damping
    #pragma omp parallel for schedule(static)
    for (int y = 0; y < rows; ++y) {
        cv::Vec3b* pDst = result1x.ptr<cv::Vec3b>(y);
        for (int x = 0; x < cols; ++x) {
            cv::Vec3b& px = pDst[x];
            float Y = 0.114f * static_cast<float>(px[0]) + 0.587f * static_cast<float>(px[1]) + 0.299f * static_cast<float>(px[2]);
            if (Y <= 28.0f) {
                float u = Y / 28.0f;
                float Y_tone = Y * std::pow(u, 0.65f);
                float scale = Y_tone / std::max(Y, 0.001f);
                px[0] = cv::saturate_cast<uchar>(static_cast<float>(px[0]) * scale);
                px[1] = cv::saturate_cast<uchar>(static_cast<float>(px[1]) * scale);
                px[2] = cv::saturate_cast<uchar>(static_cast<float>(px[2]) * scale);
            }
        }
    }

    // Micro-contrast
    cv::Mat Y_mat(rows, cols, CV_8UC1);
    #pragma omp parallel for schedule(static)
    for (int y = 0; y < rows; ++y) {
        const cv::Vec3b* pDst = result1x.ptr<cv::Vec3b>(y);
        uchar* pY = Y_mat.ptr<uchar>(y);
        for (int x = 0; x < cols; ++x) {
            const cv::Vec3b& px = pDst[x];
            pY[x] = static_cast<uchar>((29 * px[0] + 150 * px[1] + 77 * px[2]) >> 8);
        }
    }
    cv::Mat Y_blur;
    cv::GaussianBlur(Y_mat, Y_blur, cv::Size(3, 3), 1.2);
    const int tau = 2;
    const float beta = 0.55f;

    #pragma omp parallel for schedule(static)
    for (int y = 0; y < rows; ++y) {
        const uchar* pY = Y_mat.ptr<uchar>(y);
        const uchar* pYBlur = Y_blur.ptr<uchar>(y);
        cv::Vec3b* pDst = result1x.ptr<cv::Vec3b>(y);
        for (int x = 0; x < cols; ++x) {
            int D = static_cast<int>(pY[x]) - static_cast<int>(pYBlur[x]);
            int absD = std::abs(D);
            if (absD > tau) {
                float sign = (D > 0) ? 1.0f : -1.0f;
                float deltaY = sign * std::min(static_cast<float>(absD - tau) * beta, 16.0f);
                cv::Vec3b& px = pDst[x];
                px[0] = cv::saturate_cast<uchar>(static_cast<float>(px[0]) + deltaY);
                px[1] = cv::saturate_cast<uchar>(static_cast<float>(px[1]) + deltaY);
                px[2] = cv::saturate_cast<uchar>(static_cast<float>(px[2]) + deltaY);
            }
        }
    }

    return result1x;
}
