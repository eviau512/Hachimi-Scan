#include "burst_fusion.h"
#include "vulkan_compute_engine.h"
#include <vector>
#include <algorithm>
#include <cmath>

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
        return false;
    }

    // 3. 汉明距离交叉匹配
    cv::BFMatcher matcher(cv::NORM_HAMMING, true);
    std::vector<cv::DMatch> matches;
    matcher.match(descSrc, descRef, matches);

    if (matches.size() < 12) return false;

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
    if (H.empty()) return false;

    int inlierCount = cv::countNonZero(inlierMask);
    if (inlierCount < 20 || static_cast<float>(inlierCount) / goodCount < 0.30f) {
        return false;
    }

    outH = H;
    // SPEC_12 §2.2: 升级为 cv::INTER_CUBIC 双三次插值，杜绝双线性低通模糊
    cv::warpPerspective(src, outWarped, H, ref.size(), cv::INTER_CUBIC, cv::BORDER_CONSTANT, cv::Scalar(0, 0, 0));
    return true;
}

// ============================================================================
// 高亮过渡区光度自适应增益比估算 (SPEC_12 §2.3)
// ============================================================================
float BurstFusionEngine::estimateHighlightAdaptationGain(
    const cv::Mat& bgrBase,
    const cv::Mat& bgrCand,
    const cv::Mat& validMask) {

    int rows = bgrBase.rows;
    int cols = bgrBase.cols;

    std::vector<float> ratios;
    ratios.reserve(5000);

    // 跨步采样高亮未溢出过渡区: Y0 in [210, 240]，辅助帧 Y1 >= 30 具备充足信噪比
    int step = std::max(1, static_cast<int>(std::sqrt((rows * cols) / 5000.0f)));
    for (int y = 0; y < rows; y += step) {
        const cv::Vec3b* pB = bgrBase.ptr<cv::Vec3b>(y);
        const cv::Vec3b* pC = bgrCand.ptr<cv::Vec3b>(y);
        const uchar* pV = validMask.ptr<uchar>(y);

        for (int x = 0; x < cols; x += step) {
            if (pV[x] == 0) continue;
            const cv::Vec3b& b0 = pB[x];
            const cv::Vec3b& bk = pC[x];

            // Rec. 601 亮度计算: 0.114*B + 0.587*G + 0.299*R
            float y0 = 0.114f * static_cast<float>(b0[0]) + 0.587f * static_cast<float>(b0[1]) + 0.299f * static_cast<float>(b0[2]);
            float y1 = 0.114f * static_cast<float>(bk[0]) + 0.587f * static_cast<float>(bk[1]) + 0.299f * static_cast<float>(bk[2]);

            if (y0 >= 210.0f && y0 <= 240.0f && y1 >= 30.0f) {
                ratios.push_back(y0 / y1);
            }
        }
    }

    if (ratios.size() < 50) {
        return 1.55f; // -2.0 EV 典型经验默认增益比
    }

    size_t midIdx = ratios.size() / 2;
    std::nth_element(ratios.begin(), ratios.begin() + midIdx, ratios.end());
    float medRatio = ratios[midIdx];

    return std::clamp(medRatio, 1.05f, 2.5f);
}

// ============================================================================
// 真正过曝饱和嫁接与高光微细节保真 HDR 融合总入口 (SPEC_12)
// ============================================================================
// ============================================================================
// 真正过曝饱和嫁接与高光微细节保真 HDR 融合 / 1分4 50MP 亚像素超分 (SPEC_12 & 50MP Super-Res)
// ============================================================================
cv::Mat BurstFusionEngine::fuseBurstFrames(const std::vector<cv::Mat>& burstFrames, bool removeGlare, bool isScreenMode, bool superResolution) {
    if (burstFrames.empty()) return cv::Mat();
    if (burstFrames.size() == 1 && !superResolution) return burstFrames[0].clone();

    const cv::Mat& baseFrame = burstFrames[0];
    int rows = baseFrame.rows;
    int cols = baseFrame.cols;

    // ------------------------------------------------------------------------
    // 分支 A: 1分4 50MP 亚像素多帧超分重建 (Super-Resolution Zoom Fusion)
    // ------------------------------------------------------------------------
    if (superResolution) {
        int superRows = rows * 2;
        int superCols = cols * 2;

        // 1. 基准帧双三次高质量插值升采样至 2x (8160 x 6144) 作为高分辨率初始骨架
        cv::Mat baseSuper;
        cv::resize(baseFrame, baseSuper, cv::Size(superCols, superRows), 0, 0, cv::INTER_CUBIC);

        cv::Mat accum(superRows, superCols, CV_32FC3);
        baseSuper.convertTo(accum, CV_32FC3);

        cv::Mat weights(superRows, superCols, CV_32FC1, cv::Scalar(1.0f));

        // 预计算时域光度差高斯核权重查找表 (避免 1.5 亿次跨核心 std::exp 重复求值)
        static float expLUT[256];
        static bool expLutInit = false;
        if (!expLutInit) {
            for (int i = 0; i < 256; ++i) {
                float d = static_cast<float>(i);
                expLUT[i] = std::exp(-(d * d) / (2.0f * 18.0f * 18.0f));
            }
            expLutInit = true;
        }

        cv::Mat validMaskSrc = cv::Mat::ones(baseFrame.size(), CV_8UC1) * 255;

        cv::Mat frame1Super;
        float alphaFrame1 = 2.0f;

        // 2. 遍历辅帧，计算单应性并换算到 2x 亚像素坐标系进行多相核累加
        for (size_t k = 1; k < burstFrames.size(); ++k) {
            const cv::Mat& candFrame = burstFrames[k];
            cv::Mat candWarped1x, H;
            if (!alignFrameHomography(candFrame, baseFrame, candWarped1x, H)) {
                continue;
            }

            // 构造 1x 辅帧输入到 2x 超分画布的单应性矩阵: H_2x = S_2 * H
            cv::Mat S2 = (cv::Mat_<double>(3, 3) <<
                2.0, 0.0, 0.0,
                0.0, 2.0, 0.0,
                0.0, 0.0, 1.0);
            cv::Mat H2x = S2 * H;

            // 辅帧通过亚像素单应性矩阵投射至 2x 高清画布 (cv::INTER_LINEAR 速度提升 4 倍且完美保留亚像素相位差)
            cv::Mat candWarpedSuper;
            cv::warpPerspective(candFrame, candWarpedSuper, H2x, cv::Size(superCols, superRows), cv::INTER_LINEAR, cv::BORDER_CONSTANT, cv::Scalar(0, 0, 0));

            cv::Mat validMaskSuper;
            cv::warpPerspective(validMaskSrc, validMaskSuper, H2x, cv::Size(superCols, superRows), cv::INTER_NEAREST, cv::BORDER_CONSTANT, cv::Scalar(0));

            float alpha = isScreenMode ? estimateHighlightAdaptationGain(baseSuper, candWarpedSuper, validMaskSuper) : 1.0f;

            if (k == 1 && isScreenMode) {
                frame1Super = candWarpedSuper.clone();
                alphaFrame1 = alpha;
            }

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

                    // 当处于 HDR 融合模式且当前帧为欠曝光高光帧 (alpha > 1.3) 时，对高光区执行 50MP 嫁接
                    if (isScreenMode && alpha > 1.3f) {
                        if (y0 >= 235) {
                            float u = std::clamp((y0 - 235.0f) / 15.0f, 0.0f, 1.0f);
                            float m = 3.0f * u * u - 2.0f * u * u * u;
                            for (int c = 0; c < 3; ++c) {
                                float v0 = static_cast<float>(b0[c]);
                                float vkAdapted = std::min(v0, alpha * static_cast<float>(bk[c]));
                                float val = (1.0f - m) * v0 + m * vkAdapted;
                                pAccum[x][c] += 2.0f * val;
                            }
                            pWeight[x] += 2.0f;
                        }
                        continue;
                    }

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

        // 3. 归一化融合结果 (多核并行加速)
        cv::Mat superResult(superRows, superCols, CV_8UC3);
        #pragma omp parallel for schedule(static)
        for (int y = 0; y < superRows; ++y) {
            const cv::Vec3f* pAccum = accum.ptr<cv::Vec3f>(y);
            const float* pWeight = weights.ptr<float>(y);
            cv::Vec3b* pDst = superResult.ptr<cv::Vec3b>(y);

            for (int x = 0; x < superCols; ++x) {
                float invW = 1.0f / std::max(pWeight[x], 0.001f);
                pDst[x][0] = cv::saturate_cast<uchar>(pAccum[x][0] * invW);
                pDst[x][1] = cv::saturate_cast<uchar>(pAccum[x][1] * invW);
                pDst[x][2] = cv::saturate_cast<uchar>(pAccum[x][2] * invW);
            }
        }

        // ─── Vulkan GPU Acceleration Path (SPEC_17) ───
        bool vulkanSuccess = false;
        if (isScreenMode && !frame1Super.empty() && VulkanComputeEngine::getInstance().isSupported()) {
            cv::Mat baseRgba, shortRgba, outRgba;
            cv::cvtColor(superResult, baseRgba, cv::COLOR_BGR2RGBA);
            cv::cvtColor(frame1Super, shortRgba, cv::COLOR_BGR2RGBA);

            if (VulkanComputeEngine::getInstance().processHdrLtm(
                    baseRgba, shortRgba, outRgba,
                    alphaFrame1, 8.0f, 1.15f, 28.0f, 0.65f, true
            )) {
                cv::cvtColor(outRgba, superResult, cv::COLOR_RGBA2BGR);
                vulkanSuccess = true;
            }
        }

        // CPU Fallback Path (if Vulkan was not supported or failed)
        if (!vulkanSuccess && isScreenMode) {
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
        }

        // 4. 自适应微反差质感合成 (SPEC_13 §2.3) 针对 50MP 优化 (使用 8 位 NEON 加速高斯滤波)
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
        const float beta = 0.65f;

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
                    float deltaY = sign * std::min(static_cast<float>(absD - tau) * beta, 18.0f);
                    cv::Vec3b& px = pDst[x];
                    px[0] = cv::saturate_cast<uchar>(static_cast<float>(px[0]) + deltaY);
                    px[1] = cv::saturate_cast<uchar>(static_cast<float>(px[1]) + deltaY);
                    px[2] = cv::saturate_cast<uchar>(static_cast<float>(px[2]) + deltaY);
                }
            }
        }

        return superResult;
    }

    // ------------------------------------------------------------------------
    // 分支 B: 经典基准锁定过曝饱和嫁接 HDR 融合 (SPEC_12)
    // ------------------------------------------------------------------------
    cv::Mat result = baseFrame.clone();

    // 对辅助帧逐一执行单应性粗对齐与高光单向嫁接 (SPEC_12)
    for (size_t k = 1; k < burstFrames.size(); ++k) {
        const cv::Mat& candFrame = burstFrames[k];
        cv::Mat candWarped, H;
        if (!alignFrameHomography(candFrame, baseFrame, candWarped, H)) {
            continue; // 单应性粗对齐失败跳过该帧
        }

        // 跟踪有效视野掩模 (边界外的填充黑边不参与任何融合)
        cv::Mat validMaskSrc = cv::Mat::ones(candFrame.size(), CV_8UC1) * 255;
        cv::Mat validMask;
        cv::warpPerspective(validMaskSrc, validMask, H, baseFrame.size(), cv::INTER_NEAREST, cv::BORDER_CONSTANT, cv::Scalar(0));

        // 估算高光过渡区光度自适应增益比 (alpha)
        float alpha = estimateHighlightAdaptationGain(baseFrame, candWarped, validMask);

        // 遍历所有像素，执行真正物理过曝平滑饱和嫁接 (SPEC_12 §2.1 & §2.3)
        for (int y = 0; y < rows; ++y) {
            const cv::Vec3b* pBase = baseFrame.ptr<cv::Vec3b>(y);
            const cv::Vec3b* pCandWarped = candWarped.ptr<cv::Vec3b>(y);
            const uchar* pValid = validMask.ptr<uchar>(y);
            cv::Vec3b* pDst = result.ptr<cv::Vec3b>(y);

            for (int x = 0; x < cols; ++x) {
                if (pValid[x] == 0) continue;

                const cv::Vec3b& b0 = pBase[x];
                float y0 = 0.114f * static_cast<float>(b0[0]) + 0.587f * static_cast<float>(b0[1]) + 0.299f * static_cast<float>(b0[2]);

                if (y0 < 242.0f) {
                    continue;
                }

                float u = std::clamp((y0 - 242.0f) / 10.0f, 0.0f, 1.0f);
                float m = 3.0f * u * u - 2.0f * u * u * u;

                const cv::Vec3b& bk = pCandWarped[x];
                for (int c = 0; c < 3; ++c) {
                    float v0 = static_cast<float>(b0[c]);
                    float vkAdapted = std::min(v0, alpha * static_cast<float>(bk[c]));
                    pDst[x][c] = cv::saturate_cast<uchar>((1.0f - m) * v0 + m * vkAdapted);
                }
            }
        }
    }

    // 阶段二：电影级 S-Curve 暗部黑电平压制 (SPEC_13 §2.2)
    for (int y = 0; y < rows; ++y) {
        cv::Vec3b* pDst = result.ptr<cv::Vec3b>(y);
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

    // 阶段三：自适应保边微反差与质感合成 (SPEC_13 §2.3)
    cv::Mat Y_mat(rows, cols, CV_32FC1);
    for (int y = 0; y < rows; ++y) {
        const cv::Vec3b* pDst = result.ptr<cv::Vec3b>(y);
        float* pY = Y_mat.ptr<float>(y);
        for (int x = 0; x < cols; ++x) {
            const cv::Vec3b& px = pDst[x];
            pY[x] = 0.114f * static_cast<float>(px[0]) + 0.587f * static_cast<float>(px[1]) + 0.299f * static_cast<float>(px[2]);
        }
    }

    cv::Mat Y_blur;
    cv::GaussianBlur(Y_mat, Y_blur, cv::Size(3, 3), 1.2);

    const float tau = 2.0f;
    const float beta = 0.55f;

    for (int y = 0; y < rows; ++y) {
        const float* pY = Y_mat.ptr<float>(y);
        const float* pYBlur = Y_blur.ptr<float>(y);
        cv::Vec3b* pDst = result.ptr<cv::Vec3b>(y);

        for (int x = 0; x < cols; ++x) {
            float D = pY[x] - pYBlur[x];
            float absD = std::abs(D);
            if (absD > tau) {
                float sign = (D > 0.0f) ? 1.0f : -1.0f;
                float deltaY = sign * std::min((absD - tau) * beta, 16.0f);
                cv::Vec3b& px = pDst[x];
                px[0] = cv::saturate_cast<uchar>(static_cast<float>(px[0]) + deltaY);
                px[1] = cv::saturate_cast<uchar>(static_cast<float>(px[1]) + deltaY);
                px[2] = cv::saturate_cast<uchar>(static_cast<float>(px[2]) + deltaY);
            }
        }
    }

    return result;
}
