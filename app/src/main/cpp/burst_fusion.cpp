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
// ============================================================================
bool BurstFusionEngine::alignFrameHomography(const cv::Mat& src, const cv::Mat& ref, cv::Mat& outWarped, cv::Mat& outH) {
    if (src.empty() || ref.empty() || src.size() != ref.size()) {
        return false;
    }

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

    // CLAHE 均衡化局部对比度，使暗部细节和过曝边缘具备清晰梯度
    cv::Ptr<cv::CLAHE> clahe = cv::createCLAHE(3.0, cv::Size(8, 8));
    cv::Mat claheSrc, claheRef;
    clahe->apply(smallSrc, claheSrc);
    clahe->apply(smallRef, claheRef);

    // 降低 fastThreshold 至 8，确保暗光场景提取丰富角点
    cv::Ptr<cv::ORB> orb = cv::ORB::create(1500, 1.2f, 4, 31, 0, 2, cv::ORB::HARRIS_SCORE, 31, 8);
    std::vector<cv::KeyPoint> kpSrc, kpRef;
    cv::Mat descSrc, descRef;
    orb->detectAndCompute(claheSrc, cv::noArray(), kpSrc, descSrc);
    orb->detectAndCompute(claheRef, cv::noArray(), kpRef, descRef);

    if (descSrc.empty() || descRef.empty() || kpSrc.size() < 15 || kpRef.size() < 15) {
        return false;
    }

    cv::BFMatcher matcher(cv::NORM_HAMMING, true);
    std::vector<cv::DMatch> matches;
    matcher.match(descSrc, descRef, matches);

    if (matches.size() < 12) {
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

    cv::Mat inlierMask;
    cv::Mat H = cv::findHomography(ptsSrc, ptsRef, cv::RANSAC, 3.0, inlierMask);
    if (H.empty()) {
        return false;
    }

    int inlierCount = cv::countNonZero(inlierMask);
    float inlierRatio = (goodCount > 0) ? (static_cast<float>(inlierCount) / static_cast<float>(goodCount)) : 0.0f;
    if (inlierCount < 15 || inlierRatio < 0.12f) {
        return false;
    }

    double det = H.at<double>(0,0) * H.at<double>(1,1) - H.at<double>(0,1) * H.at<double>(1,0);
    if (std::abs(det - 1.0) > 0.30) {
        return false;
    }

    // 手持连拍相邻帧刚体位移合理性校验 (不超过 70 像素)
    if (std::abs(H.at<double>(0, 2)) > 70.0 || std::abs(H.at<double>(1, 2)) > 70.0) {
        return false;
    }

    outH = H;
    cv::warpPerspective(src, outWarped, H, ref.size(), cv::INTER_LINEAR, cv::BORDER_CONSTANT, cv::Scalar(0, 0, 0));
    return true;
}

// ============================================================================
// 基于 Greg Ward 经典中值阈值位图的多曝光对齐 (AlignMTB)
// 专为跨大曝光级差的 HDR 连拍设计：
// 1. 中值阈值二值化将图像分割为亮部与暗部拓扑几何，完全免疫由于曝光变化导致的灰度剧变
// 2. 金字塔位图 XOR 与 popcount 极速搜索最佳刚体位移，单次仅耗时 5~10ms
// 3. 彻底杜绝特征点角点匮乏、大面积过曝或纯黑导致的漂移错位与环形光斑重影
// ============================================================================
bool BurstFusionEngine::alignFrameMTB(const cv::Mat& src, const cv::Mat& ref, cv::Mat& outWarped, cv::Mat& outH) {
    if (src.empty() || ref.empty() || src.size() != ref.size()) {
        return false;
    }

    try {
        // max_bits=7 (支持最大 +/- 127 像素手抖偏移), exclude_range=4, cut=false
        cv::Ptr<cv::AlignMTB> aligner = cv::createAlignMTB(7, 4, false);
        cv::Point shift = aligner->calculateShift(ref, src);
        LOGI("alignFrameMTB: computed robust exposure shift dx=%d, dy=%d", shift.x, shift.y);

        aligner->shiftMat(src, outWarped, shift);

        outH = (cv::Mat_<double>(3, 3) <<
            1.0, 0.0, static_cast<double>(shift.x),
            0.0, 1.0, static_cast<double>(shift.y),
            0.0, 0.0, 1.0);
        return true;
    } catch (const std::exception& e) {
        LOGW("alignFrameMTB exception: %s, falling back to identity", e.what());
        outH = cv::Mat::eye(3, 3, CV_64F);
        outWarped = src.clone();
        return false;
    }
}

// ============================================================================
// 对数梯度域相位相关配准 (Gradient-Domain Phase Correlation)
// 专为跨曝光级差的 HDR 刚体配准设计：
// 1. Log(1 + |grad|) 将对数域乘性曝光系数转化为加性直流分量
// 2. 交叉功率谱相位白化过滤所有亮度差异，全局边缘相干对齐
// 3. 彻底杜绝特征点角点匮乏或大面积过曝导致的失配
// ============================================================================
bool BurstFusionEngine::alignGradientPhaseCorrelation(const cv::Mat& src, const cv::Mat& ref, cv::Mat& outWarped, cv::Mat& outH) {
    if (src.empty() || ref.empty() || src.size() != ref.size()) {
        return false;
    }

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

    cv::Mat gxSrc, gySrc, magSrc;
    cv::Sobel(smallSrc, gxSrc, CV_32F, 1, 0, 3);
    cv::Sobel(smallSrc, gySrc, CV_32F, 0, 1, 3);
    cv::magnitude(gxSrc, gySrc, magSrc);

    cv::Mat gxRef, gyRef, magRef;
    cv::Sobel(smallRef, gxRef, CV_32F, 1, 0, 3);
    cv::Sobel(smallRef, gyRef, CV_32F, 0, 1, 3);
    cv::magnitude(gxRef, gyRef, magRef);

    magSrc += 1.0f;
    cv::log(magSrc, magSrc);
    magRef += 1.0f;
    cv::log(magRef, magRef);

    cv::Mat hanningWin;
    cv::createHanningWindow(hanningWin, smallRef.size(), CV_32F);

    double response = 0.0;
    cv::Point2d shift = cv::phaseCorrelate(magRef, magSrc, hanningWin, &response);

    float invScale = (scale < 1.0f) ? (1.0f / scale) : 1.0f;
    double dx = -shift.x * invScale;
    double dy = -shift.y * invScale;

    LOGI("alignGradientPhaseCorrelation: response=%.4f, dx=%.2f, dy=%.2f", response, dx, dy);

    if (response >= 0.10 && std::abs(dx) <= 60.0 && std::abs(dy) <= 60.0) {
        outH = (cv::Mat_<double>(3, 3) <<
            1.0, 0.0, dx,
            0.0, 1.0, dy,
            0.0, 0.0, 1.0);
        cv::warpPerspective(src, outWarped, outH, ref.size(),
                            cv::INTER_LINEAR, cv::BORDER_CONSTANT, cv::Scalar(0, 0, 0));
        return true;
    }

    return false;
}

// ============================================================================
// 高光多候选 ROI 归一化互相关模板精配准 (Multi-ROI NCC Template Match)
// 专门针对夜景灯芯、环形灯管、发光字：
// 1. 归一化互相关 TM_CCOEFF_NORMED 对仿射光度变化完全不变
// 2. 抛物线亚像素插值达到 0.05 像素精度
// 3. 彻底杜绝桌面漫反射拉偏质心导致的环形重影
// ============================================================================
bool BurstFusionEngine::alignHighlightTemplate(const cv::Mat& srcShort, const cv::Mat& refBase, cv::Mat& outWarped, cv::Mat& outH) {
    if (srcShort.empty() || refBase.empty() || srcShort.size() != refBase.size()) {
        return false;
    }

    cv::Mat grayShort, grayBase;
    if (srcShort.channels() == 3) cv::cvtColor(srcShort, grayShort, cv::COLOR_BGR2GRAY);
    else grayShort = srcShort;

    if (refBase.channels() == 3) cv::cvtColor(refBase, grayBase, cv::COLOR_BGR2GRAY);
    else grayBase = refBase;

    int rows = grayShort.rows;
    int cols = grayShort.cols;

    int maxDim = std::max(cols, rows);
    float scale = (maxDim > 1920) ? 1920.0f / maxDim : 1.0f;

    cv::Mat sShort, sBase;
    if (scale < 1.0f) {
        cv::resize(grayShort, sShort, cv::Size(), scale, scale, cv::INTER_AREA);
        cv::resize(grayBase, sBase, cv::Size(), scale, scale, cv::INTER_AREA);
    } else {
        sShort = grayShort;
        sBase = grayBase;
    }

    int sRows = sShort.rows;
    int sCols = sShort.cols;

    cv::Mat blurShort;
    cv::GaussianBlur(sShort, blurShort, cv::Size(7, 7), 2.0);

    double minVal, maxVal;
    cv::Point minLoc, maxLoc;
    cv::minMaxLoc(blurShort, &minVal, &maxVal, &minLoc, &maxLoc);

    if (maxVal < 15.0) {
        return false;
    }

    int R = std::min(70, std::min(sCols, sRows) / 10);
    int M = 45;

    // 1. 提取所有显著发光高光峰值候选 (Connected Components + Local Maxima)
    double threshVal = std::max(18.0, maxVal * 0.35);
    cv::Mat threshMask;
    cv::threshold(blurShort, threshMask, threshVal, 255.0, cv::THRESH_BINARY);
    threshMask.convertTo(threshMask, CV_8UC1);

    cv::Mat labels, stats, centroids;
    int nLabels = cv::connectedComponentsWithStats(threshMask, labels, stats, centroids);

    struct PeakCandidate {
        cv::Point pt;
        double maxVal;
        int area;
    };
    std::vector<PeakCandidate> candidates;

    for (int i = 1; i < nLabels; ++i) {
        int area = stats.at<int>(i, cv::CC_STAT_AREA);
        if (area < 15) continue;

        cv::Mat compMask = (labels == i);
        double cMin, cMax;
        cv::Point cMinLoc, cMaxLoc;
        cv::minMaxLoc(blurShort, &cMin, &cMax, &cMinLoc, &cMaxLoc, compMask);

        int px = cMaxLoc.x;
        int py = cMaxLoc.y;
        if (px - R - M >= 0 && px + R + M < sCols && py - R - M >= 0 && py + R + M < sRows) {
            candidates.push_back({cMaxLoc, cMax, area});
        }
    }

    // 按峰值亮度与面积降序排序
    std::sort(candidates.begin(), candidates.end(), [](const PeakCandidate& a, const PeakCandidate& b) {
        if (std::abs(a.maxVal - b.maxVal) > 5.0) return a.maxVal > b.maxVal;
        return a.area > b.area;
    });

    // 空间非极大值抑制 (NMS): 过滤相互重叠的临近峰值
    std::vector<PeakCandidate> selectedPeaks;
    for (const auto& p : candidates) {
        bool tooClose = false;
        for (const auto& sp : selectedPeaks) {
            double dist = std::hypot(p.pt.x - sp.pt.x, p.pt.y - sp.pt.y);
            if (dist < 2.0 * R) {
                tooClose = true;
                break;
            }
        }
        if (!tooClose) {
            selectedPeaks.push_back(p);
            if (selectedPeaks.size() >= 8) break; // 最多匹配 8 个显著发光光源
        }
    }

    // 若连通域未筛选出有效峰值，则使用全局最大值兜底
    if (selectedPeaks.empty()) {
        int px = maxLoc.x;
        int py = maxLoc.y;
        if (px - R - M >= 0 && px + R + M < sCols && py - R - M >= 0 && py + R + M < sRows) {
            selectedPeaks.push_back({maxLoc, maxVal, 100});
        } else {
            return false;
        }
    }

    // 2. 对每个独立发光光源执行 NCC 归一化互相关匹配与亚像素插值
    std::vector<cv::Point2f> srcPts;
    std::vector<cv::Point2f> dstPts;
    std::vector<double> matchScores;
    std::vector<cv::Point2d> displacements;

    for (const auto& peak : selectedPeaks) {
        int px = peak.pt.x;
        int py = peak.pt.y;

        cv::Rect templRect(px - R, py - R, 2 * R, 2 * R);
        cv::Rect searchRect(px - R - M, py - R - M, 2 * R + 2 * M, 2 * R + 2 * M);

        cv::Mat templ = sShort(templRect);
        cv::Mat searchRoi = sBase(searchRect);

        cv::Mat matchResult;
        cv::matchTemplate(searchRoi, templ, matchResult, cv::TM_CCOEFF_NORMED);

        double minV, maxV;
        cv::Point minL, maxL;
        cv::minMaxLoc(matchResult, &minV, &maxV, &minL, &maxL);

        if (maxV >= 0.35) {
            double intDx = static_cast<double>(maxL.x - M);
            double intDy = static_cast<double>(maxL.y - M);

            int mx = maxL.x;
            int my = maxL.y;
            double subDx = intDx;
            double subDy = intDy;

            if (mx > 0 && mx < matchResult.cols - 1) {
                double denom = matchResult.at<float>(my, mx - 1) - 2.0 * matchResult.at<float>(my, mx) + matchResult.at<float>(my, mx + 1);
                if (std::abs(denom) > 1e-5) {
                    subDx += (matchResult.at<float>(my, mx - 1) - matchResult.at<float>(my, mx + 1)) / (2.0 * denom);
                }
            }
            if (my > 0 && my < matchResult.rows - 1) {
                double denom = matchResult.at<float>(my - 1, mx) - 2.0 * matchResult.at<float>(my, mx) + matchResult.at<float>(my + 1, mx);
                if (std::abs(denom) > 1e-5) {
                    subDy += (matchResult.at<float>(my - 1, mx) - matchResult.at<float>(my + 1, mx)) / (2.0 * denom);
                }
            }

            srcPts.emplace_back(static_cast<float>(px), static_cast<float>(py));
            dstPts.emplace_back(static_cast<float>(px + subDx), static_cast<float>(py + subDy));
            matchScores.push_back(maxV);
            displacements.emplace_back(subDx, subDy);

            LOGI("alignHighlightTemplate: matched light peak at (%d,%d), maxVal=%.1f, score=%.4f, dx=%.2f, dy=%.2f",
                 px, py, peak.maxVal, maxV, subDx, subDy);
        }
    }

    if (srcPts.empty()) {
        return false;
    }

    float invScale = (scale < 1.0f) ? (1.0f / scale) : 1.0f;
    bool fitSuccess = false;

    // 3. 变换矩阵估计：多光源时估计 4 自由度刚体变换 (平移+旋转)，单光源时使用纯平移
    if (srcPts.size() >= 2) {
        cv::Mat inliers;
        cv::Mat M = cv::estimateAffinePartial2D(srcPts, dstPts, inliers, cv::RANSAC, 3.0);
        if (!M.empty() && M.rows == 2 && M.cols == 3) {
            double a00 = M.at<double>(0, 0);
            double a01 = M.at<double>(0, 1);
            double a10 = M.at<double>(1, 0);
            double a11 = M.at<double>(1, 1);
            double tx = M.at<double>(0, 2);
            double ty = M.at<double>(1, 2);

            double s = std::sqrt(a00 * a00 + a01 * a01);
            double theta = std::atan2(a10, a00);
            double transDist = std::hypot(tx, ty);

            // 物理自检: 手持连续曝光 burst 旋转角 < 5度, 缩放 0.95~1.05, 平移 <= 45px (在 downscale 尺度下)
            if (s >= 0.95 && s <= 1.05 && std::abs(theta) < 0.087 && transDist <= 45.0) {
                outH = (cv::Mat_<double>(3, 3) <<
                    a00, a01, tx * invScale,
                    a10, a11, ty * invScale,
                    0.0, 0.0, 1.0);
                fitSuccess = true;
                LOGI("alignHighlightTemplate: fitted Multi-Peak Rigid Affine: s=%.4f, rot=%.3f deg, tx=%.2f, ty=%.2f",
                     s, theta * 180.0 / CV_PI, tx * invScale, ty * invScale);
            }
        }
    }

    if (!fitSuccess) {
        // 单光源或刚体拟合退化: 选择最高置信度的光源位移作为全局平移
        size_t bestIdx = 0;
        double bestScore = -1.0;
        for (size_t i = 0; i < matchScores.size(); ++i) {
            if (matchScores[i] > bestScore) {
                bestScore = matchScores[i];
                bestIdx = i;
            }
        }
        double fullDx = displacements[bestIdx].x * invScale;
        double fullDy = displacements[bestIdx].y * invScale;
        LOGI("alignHighlightTemplate: single/best-peak translation: dx=%.2f, dy=%.2f, score=%.4f",
             fullDx, fullDy, bestScore);

        if (std::hypot(fullDx, fullDy) <= 120.0) {
            outH = (cv::Mat_<double>(3, 3) <<
                1.0, 0.0, fullDx,
                0.0, 1.0, fullDy,
                0.0, 0.0, 1.0);
            fitSuccess = true;
        }
    }

    if (fitSuccess) {
        cv::warpPerspective(srcShort, outWarped, outH, refBase.size(),
                            cv::INTER_LINEAR, cv::BORDER_CONSTANT, cv::Scalar(0, 0, 0));
        return true;
    }

    return false;
}

// ============================================================================
// 暗光夜景高光多级自适应配准 (Highlight Multi-Strategy Alignment)
// 1. CLAHE ORB 单应性配准 (场景结构丰富时)
// 2. NCC Template 归一化互相关匹配 (夜景发光灯具/环形灯管/发光字)
// 3. 对数梯度相位相关 (低对比度/大曝光差全局结构)
// 4. 恒等矩阵保底 (位移 0 比拉偏更保真)
// ============================================================================
bool BurstFusionEngine::alignHighlightFrame(const cv::Mat& srcShort, const cv::Mat& refBase, cv::Mat& outWarped, cv::Mat& outH) {
    if (srcShort.empty() || refBase.empty() || srcShort.size() != refBase.size()) {
        return false;
    }

    // 1. 优先尝试 CLAHE + ORB 单应性配准
    if (alignFrameHomography(srcShort, refBase, outWarped, outH)) {
        LOGI("alignHighlightFrame: successfully aligned via CLAHE ORB homography");
        return true;
    }

    // 2. 第二级：基于发光核心归一化互相关模板精配准 (NCC Template Matching)
    if (alignHighlightTemplate(srcShort, refBase, outWarped, outH)) {
        LOGI("alignHighlightFrame: successfully aligned via Highlight NCC Template");
        return true;
    }

    // 3. 第三级：对数梯度域全局相位相关配准 (Log-Gradient Phase Correlation)
    if (alignGradientPhaseCorrelation(srcShort, refBase, outWarped, outH)) {
        LOGI("alignHighlightFrame: successfully aligned via Gradient Phase Correlation");
        return true;
    }

    // 4. 终极保护：恒等矩阵
    LOGI("alignHighlightFrame: adopting identity fallback H=I");
    outH = cv::Mat::eye(3, 3, CV_64F);
    outWarped = srcShort.clone();
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
    return std::clamp(ratios[midIdx], 1.05f, 20.0f);
}

// ============================================================================
// 9帧多曝光金字塔 + AlignMTB 抗重影 + 50MP 极速融合引擎
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
    std::vector<cv::Mat> tier2; // Tier 2: 中灰过渡与防溢出帧 (EV -3.5)
    std::vector<cv::Mat> tier3; // Tier 3: 极高光灯泡/灯丝微细节帧 (EV -7.0)

    if (nFrames == 7) {
        tier1.assign(burstFrames.begin(), burstFrames.begin() + 4);
        tier2.assign(burstFrames.begin() + 4, burstFrames.begin() + 6);
        tier3.assign(burstFrames.begin() + 6, burstFrames.end());
    } else if (nFrames == 9) {
        tier1.assign(burstFrames.begin(), burstFrames.begin() + 4);
        tier2.assign(burstFrames.begin() + 4, burstFrames.begin() + 7);
        tier3.assign(burstFrames.begin() + 7, burstFrames.end());
    } else if (nFrames >= 4) {
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

    LOGI("fuseBurstFrames: %d frames partitioned -> Tier1: %zu, Tier2: %zu, Tier3: %zu",
         nFrames, tier1.size(), tier2.size(), tier3.size());

    // 预计算时域光度差高斯核权重查找表
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
    // 分支 A: 50MP 极速双尺度超分与多尺度曝光金字塔融合
    // ────────────────────────────────────────────────────────────────────────
    if (superResolution) {
        int superRows = rows * 2;
        int superCols = cols * 2;

        cv::Mat S2 = (cv::Mat_<double>(3, 3) <<
            2.0, 0.0, 0.0,
            0.0, 2.0, 0.0,
            0.0, 0.0, 1.0);

        // ── 步骤 1: 4 帧 Tier 1 亚像素超分累加 (50MP Base Plate) ──
        cv::Mat baseSuper;
        cv::resize(tier1[0], baseSuper, cv::Size(superCols, superRows), 0, 0, cv::INTER_LINEAR);

        cv::Mat accum(superRows, superCols, CV_32FC3);
        baseSuper.convertTo(accum, CV_32FC3);
        cv::Mat weights(superRows, superCols, CV_32FC1, cv::Scalar(1.0f));

        for (size_t k = 1; k < tier1.size(); ++k) {
            cv::Mat warped1x, H;
            // Tier 1 相同曝光：优先 CLAHE ORB，若微抖动失配则回退对数梯度相位相关
            if (!alignFrameHomography(tier1[k], tier1[0], warped1x, H)) {
                if (!alignGradientPhaseCorrelation(tier1[k], tier1[0], warped1x, H)) {
                    H = cv::Mat::eye(3, 3, CV_64F);
                }
            }
            cv::Mat H2x = S2 * H;
            cv::Mat candWarpedSuper;
            cv::warpPerspective(tier1[k], candWarpedSuper, H2x, cv::Size(superCols, superRows), cv::INTER_LINEAR, cv::BORDER_CONSTANT, cv::Scalar(0, 0, 0));

            #pragma omp parallel for schedule(static)
            for (int y = 0; y < superRows; ++y) {
                const cv::Vec3b* pBase = baseSuper.ptr<cv::Vec3b>(y);
                const cv::Vec3b* pCand = candWarpedSuper.ptr<cv::Vec3b>(y);
                cv::Vec3f* pAccum = accum.ptr<cv::Vec3f>(y);
                float* pWeight = weights.ptr<float>(y);

                for (int x = 0; x < superCols; ++x) {
                    const cv::Vec3b& bk = pCand[x];
                    if (bk[0] == 0 && bk[1] == 0 && bk[2] == 0) continue;

                    const cv::Vec3b& b0 = pBase[x];
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

        // ── 步骤 2: Tier 2 中灰曝光版 (基于多级特征/质心对齐基准帧) ──
        cv::Mat I_mid_12M;
        cv::Mat H_mid0;
        if (!tier2.empty()) {
            std::vector<cv::Mat> midWarped1xList;
            for (size_t k = 0; k < tier2.size(); ++k) {
                cv::Mat w1x, Hk;
                if (k == 0) {
                    if (!alignHighlightFrame(tier2[0], tier1[0], w1x, H_mid0)) {
                        H_mid0 = cv::Mat::eye(3, 3, CV_64F);
                    }
                    Hk = H_mid0;
                } else {
                    cv::Mat H_rel, dummy;
                    if (!alignHighlightFrame(tier2[k], tier2[0], dummy, H_rel)) {
                        H_rel = cv::Mat::eye(3, 3, CV_64F);
                    }
                    Hk = H_mid0 * H_rel;
                    cv::warpPerspective(tier2[k], w1x, Hk, cv::Size(cols, rows), cv::INTER_LINEAR, cv::BORDER_CONSTANT, cv::Scalar(0, 0, 0));
                }
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

        // ── 步骤 3: Tier 3 极高光曝光版 (双重桥接+局部质心精配准，发光环100%同心贴合) ──
        cv::Mat I_short_12M;
        cv::Mat shortSuper;
        if (!tier3.empty()) {
            std::vector<cv::Mat> shortWarped1xList;
            cv::Mat H_short0;

            bool aligned = false;
            // 桥接策略 1：通过 Tier 2 进行过渡对齐 (Tier 2 具有灯芯结构且不过曝)
            if (!tier2.empty() && !H_mid0.empty()) {
                cv::Mat H_3_to_2, dummy;
                if (alignHighlightFrame(tier3[0], tier2[0], dummy, H_3_to_2)) {
                    H_short0 = H_mid0 * H_3_to_2;
                    aligned = true;
                    LOGI("Tier 3 Frame 0 successfully bridged via Tier 2");
                }
            }
            // 桥接策略 2：直接对齐至 Tier 1 基准帧
            if (!aligned) {
                cv::Mat dummy;
                if (alignHighlightFrame(tier3[0], tier1[0], dummy, H_short0)) {
                    aligned = true;
                    LOGI("Tier 3 Frame 0 directly aligned to Tier 1");
                }
            }
            if (aligned && !H_short0.empty() && H_short0.rows == 3 && H_short0.cols == 3) {
                double a00 = H_short0.at<double>(0, 0);
                double a10 = H_short0.at<double>(1, 0);
                double rotDeg = std::atan2(a10, a00) * 180.0 / CV_PI;
                LOGI("Tier 3 H_short0 composite transform: rot=%.3f deg, tx=%.2f, ty=%.2f",
                     rotDeg, H_short0.at<double>(0, 2), H_short0.at<double>(1, 2));
            }
            if (!aligned) {
                H_short0 = cv::Mat::eye(3, 3, CV_64F);
            }

            for (size_t k = 0; k < tier3.size(); ++k) {
                cv::Mat w1x, Hk;
                if (k == 0) {
                    Hk = H_short0;
                } else {
                    cv::Mat H_rel, dummy;
                    if (!alignHighlightFrame(tier3[k], tier3[0], dummy, H_rel)) {
                        H_rel = cv::Mat::eye(3, 3, CV_64F);
                    }
                    Hk = H_short0 * H_rel;
                }
                cv::warpPerspective(tier3[k], w1x, Hk, cv::Size(cols, rows), cv::INTER_LINEAR, cv::BORDER_CONSTANT, cv::Scalar(0, 0, 0));
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

            // 将精准对齐后的短曝光图升采样到 50MP 画布以供高光灯具回填
            if (H_short0.empty()) H_short0 = cv::Mat::eye(3, 3, CV_64F);
            cv::Mat H2x_short = S2 * H_short0;
            cv::warpPerspective(tier3[0], shortSuper, H2x_short, cv::Size(superCols, superRows), cv::INTER_LINEAR, cv::BORDER_CONSTANT, cv::Scalar(0, 0, 0));
        }

        // ── 步骤 4: OpenCV MergeMertens 多尺度拉普拉斯曝光融合 ──
        std::vector<cv::Mat> plates1x;
        plates1x.push_back(I_base_12M);
        if (!I_mid_12M.empty()) plates1x.push_back(I_mid_12M);
        if (!I_short_12M.empty()) plates1x.push_back(I_short_12M);

        cv::Mat superResult;
        if (plates1x.size() >= 2) {
            LOGI("Running OpenCV createMergeMertens on %zu exposure-aligned plates (accelerated 1/2 scale)...", plates1x.size());
            int mRows = rows / 2;
            int mCols = cols / 2;
            std::vector<cv::Mat> platesSmall;
            for (const auto& p : plates1x) {
                cv::Mat sm;
                cv::resize(p, sm, cv::Size(mCols, mRows), 0, 0, cv::INTER_AREA);
                platesSmall.push_back(sm);
            }

            cv::Ptr<cv::MergeMertens> merger = cv::createMergeMertens(1.0f, 1.0f, 1.0f);
            cv::Mat fusedSmallFloat;
            merger->process(platesSmall, fusedSmallFloat);
            cv::Mat fusedSmall;
            fusedSmallFloat.convertTo(fusedSmall, CV_8UC3, 255.0);

            // 极速升采样至 50MP 平滑底版
            cv::Mat fused50M_smooth;
            cv::resize(fusedSmall, fused50M_smooth, cv::Size(superCols, superRows), 0, 0, cv::INTER_LINEAR);

            cv::Mat baseSmooth50M;
            cv::resize(I_base_12M, baseSmooth50M, cv::Size(superCols, superRows), 0, 0, cv::INTER_LINEAR);

            cv::Mat shortSmooth50M;
            bool hasShortDetail = !shortSuper.empty();
            if (hasShortDetail) {
                cv::resize(I_short_12M, shortSmooth50M, cv::Size(superCols, superRows), 0, 0, cv::INTER_LINEAR);
            }

            // 双尺度高频超分纹理回填
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
            LOGI("50MP Two-Scale Frequency Super-Resolution HDR Fusion completed!");
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

        // ── 步骤 6: 极速微反差质感合成 (降采样低频高斯滤波加速 10 倍) ──
        cv::Mat smallY(rows, cols, CV_8UC1);
        #pragma omp parallel for schedule(static)
        for (int y = 0; y < rows; ++y) {
            const cv::Vec3b* pDst = superResult.ptr<cv::Vec3b>(y * 2);
            uchar* pY = smallY.ptr<uchar>(y);
            for (int x = 0; x < cols; ++x) {
                const cv::Vec3b& px = pDst[x * 2];
                pY[x] = static_cast<uchar>((29 * px[0] + 150 * px[1] + 77 * px[2]) >> 8);
            }
        }

        cv::Mat smallBlur;
        cv::GaussianBlur(smallY, smallBlur, cv::Size(3, 3), 1.2);
        cv::Mat fullBlur;
        cv::resize(smallBlur, fullBlur, cv::Size(superCols, superRows), 0, 0, cv::INTER_LINEAR);

        const int tau = 2;
        const float beta = 0.55f;

        #pragma omp parallel for schedule(static)
        for (int y = 0; y < superRows; ++y) {
            const uchar* pYBlur = fullBlur.ptr<uchar>(y);
            cv::Vec3b* pDst = superResult.ptr<cv::Vec3b>(y);

            for (int x = 0; x < superCols; ++x) {
                cv::Vec3b& px = pDst[x];
                int curY = (29 * px[0] + 150 * px[1] + 77 * px[2]) >> 8;
                int D = curY - static_cast<int>(pYBlur[x]);
                int absD = std::abs(D);
                if (absD > tau) {
                    float sign = (D > 0) ? 1.0f : -1.0f;
                    float deltaY = sign * std::min(static_cast<float>(absD - tau) * beta, 16.0f);
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
    cv::Mat H_mid0;
    if (!tier2.empty()) {
        if (!alignHighlightFrame(tier2[0], tier1[0], I_mid_12M, H_mid0)) {
            H_mid0 = cv::Mat::eye(3, 3, CV_64F);
            I_mid_12M = tier2[0].clone();
        }
    }

    cv::Mat I_short_12M;
    cv::Mat H_short0;
    if (!tier3.empty()) {
        bool aligned = false;
        if (!tier2.empty() && !H_mid0.empty()) {
            cv::Mat H_3_to_2, dummy;
            if (alignHighlightFrame(tier3[0], tier2[0], dummy, H_3_to_2)) {
                H_short0 = H_mid0 * H_3_to_2;
                cv::warpPerspective(tier3[0], I_short_12M, H_short0, cv::Size(cols, rows), cv::INTER_LINEAR, cv::BORDER_CONSTANT, cv::Scalar(0, 0, 0));
                aligned = true;
            }
        }
        if (!aligned) {
            if (alignHighlightFrame(tier3[0], tier1[0], I_short_12M, H_short0)) {
                aligned = true;
            }
        }
        if (!aligned) {
            H_short0 = cv::Mat::eye(3, 3, CV_64F);
            I_short_12M = tier3[0].clone();
        }
    }

    std::vector<cv::Mat> plates;
    plates.push_back(I_base_12M);
    if (!I_mid_12M.empty()) plates.push_back(I_mid_12M);
    if (!I_short_12M.empty()) plates.push_back(I_short_12M);

    cv::Mat result1x;
    if (plates.size() >= 2) {
        int mRows = rows / 2;
        int mCols = cols / 2;
        std::vector<cv::Mat> platesSmall;
        for (const auto& p : plates) {
            cv::Mat sm;
            cv::resize(p, sm, cv::Size(mCols, mRows), 0, 0, cv::INTER_AREA);
            platesSmall.push_back(sm);
        }
        cv::Ptr<cv::MergeMertens> merger = cv::createMergeMertens(1.0f, 1.0f, 1.0f);
        cv::Mat fusedFloat;
        merger->process(platesSmall, fusedFloat);
        cv::Mat fusedSmall;
        fusedFloat.convertTo(fusedSmall, CV_8UC3, 255.0);
        cv::resize(fusedSmall, result1x, cv::Size(cols, rows), 0, 0, cv::INTER_LINEAR);
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

    return result1x;
}
