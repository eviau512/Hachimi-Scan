#include "edge_detector.h"
#include <algorithm>
#include <numeric>
#include <cmath>

DetectionResult EdgeDetector::detectDocument(const cv::Mat& grayFrame, bool curvedMode, float touchX, float touchY) {
    DetectionResult result;
    result.found = false;
    result.isCurved = curvedMode;

    if (grayFrame.empty()) return result;

    // 1. 降采样至适中尺寸 (长边 ~640px)，减少高频纹理噪点，保证 60 FPS 极速处理
    int maxDim = std::max(grayFrame.cols, grayFrame.rows);
    float scale = (maxDim > 640) ? 640.0f / maxDim : 1.0f;
    cv::Mat smallFrame;
    if (scale < 1.0f) {
        cv::resize(grayFrame, smallFrame, cv::Size(), scale, scale, cv::INTER_AREA);
    } else {
        smallFrame = grayFrame;
    }

    // 计算高频笔画/文本能量密度图 (Sobel 梯度图) 与积分图，用于 MS Lens 风格的图文显著度判定
    cv::Mat gradX, gradY;
    cv::Sobel(smallFrame, gradX, CV_16S, 1, 0, 3);
    cv::Sobel(smallFrame, gradY, CV_16S, 0, 1, 3);
    cv::convertScaleAbs(gradX, gradX);
    cv::convertScaleAbs(gradY, gradY);
    cv::Mat gradMag;
    cv::addWeighted(gradX, 0.5, gradY, 0.5, 0, gradMag);
    cv::Mat intGrad;
    cv::integral(gradMag, intGrad, CV_64F);

    // 2. 对比度受限自适应直方图均衡化 (CLAHE) - 彻底解决白墙+浅色告示弱对比度问题 (SPEC_01 §2.2)
    cv::Ptr<cv::CLAHE> clahe = cv::createCLAHE(2.0, cv::Size(8, 8));
    cv::Mat claheFrame;
    clahe->apply(smallFrame, claheFrame);

    // 3. 高斯滤波平滑微小噪点
    cv::Mat blurred;
    cv::GaussianBlur(claheFrame, blurred, cv::Size(5, 5), 1.0);

    // 4. 敏感双阈值 Canny (T_low=25.0, T_high=70.0)，废弃全局 Otsu 避免大面积暗物拉高阈值
    cv::Mat edges;
    cv::Canny(blurred, edges, 25.0, 70.0);

    // 5. 形态学闭运算连接微小断裂边缘
    cv::Mat kernel = cv::getStructuringElement(cv::MORPH_RECT, cv::Size(3, 3));
    cv::morphologyEx(edges, edges, cv::MORPH_CLOSE, kernel);

    // 6. 寻找轮廓
    std::vector<std::vector<cv::Point>> contours;
    cv::findContours(edges, contours, cv::RETR_LIST, cv::CHAIN_APPROX_SIMPLE);

    if (contours.empty()) return result;

    // 按轮廓面积降序排列
    std::sort(contours.begin(), contours.end(), [](const std::vector<cv::Point>& a, const std::vector<cv::Point>& b) {
        return cv::contourArea(a) > cv::contourArea(b);
    });

    double frameArea = smallFrame.cols * smallFrame.rows;
    // SPEC_01 §2.2: 放宽最小面积门限至 0.005 * frameArea (0.5% of frame)，捕获远距离告示牌
    double minArea = 0.005 * frameArea;
    double maxArea = 0.95 * frameArea; // 排除摄像头最外边框

    int marginX = static_cast<int>(smallFrame.cols * 0.015);
    int marginY = static_cast<int>(smallFrame.rows * 0.015);

    cv::Point2f frameCenter(smallFrame.cols * 0.5f, smallFrame.rows * 0.5f);
    float frameDiag = std::hypot(smallFrame.cols, smallFrame.rows);

    double bestScore = -1.0;
    std::vector<cv::Point> bestApprox;

    for (const auto& contour : contours) {
        double area = cv::contourArea(contour);
        if (area < minArea) break;
        if (area > maxArea) continue;

        cv::Rect bRect = cv::boundingRect(contour);
        // 排除刚好贴在屏幕边缘的虚假大框
        if (bRect.x <= marginX && bRect.y <= marginY &&
            bRect.x + bRect.width >= smallFrame.cols - marginX &&
            bRect.y + bRect.height >= smallFrame.rows - marginY) {
            continue;
        }

        double perimeter = cv::arcLength(contour, true);
        std::vector<cv::Point> approx;

        // 尝试多阶自适应多边形拟合
        for (double epsFactor : {0.02, 0.025, 0.03, 0.035, 0.04}) {
            cv::approxPolyDP(contour, approx, epsFactor * perimeter, true);
            if (approx.size() == 4) break;
        }

        // 如果直接拟合不是4边形，尝试凸包拟合 (应对带折角、圆角或手指轻压的文档)
        if (approx.size() != 4) {
            std::vector<cv::Point> hull;
            cv::convexHull(contour, hull);
            double hullPerimeter = cv::arcLength(hull, true);
            for (double epsFactor : {0.02, 0.03, 0.04, 0.05}) {
                cv::approxPolyDP(hull, approx, epsFactor * hullPerimeter, true);
                if (approx.size() == 4) break;
            }
        }

        if (approx.size() == 4 && cv::isContourConvex(approx)) {
            // 校验内角：放宽至 40° ~ 140°，完美宽容现实生活中的大角度俯视视角
            bool validAngles = true;
            for (int i = 0; i < 4; ++i) {
                cv::Point p0 = approx[i];
                cv::Point p1 = approx[(i + 1) % 4];
                cv::Point p2 = approx[(i + 2) % 4];

                cv::Point2f v1(static_cast<float>(p0.x - p1.x), static_cast<float>(p0.y - p1.y));
                cv::Point2f v2(static_cast<float>(p2.x - p1.x), static_cast<float>(p2.y - p1.y));

                double dot = v1.x * v2.x + v1.y * v2.y;
                double norm = std::hypot(v1.x, v1.y) * std::hypot(v2.x, v2.y);
                if (norm > 0) {
                    double cosVal = dot / norm;
                    // cos(40°) ≈ 0.766, cos(140°) ≈ -0.766
                    if (std::abs(cosVal) > 0.77) {
                        validAngles = false;
                        break;
                    }
                }
            }

            if (validAngles) {
                // 长宽比校验 (放宽至 12.0f，支持地铁站牌、条幅、横幅与长收据)
                cv::RotatedRect r = cv::minAreaRect(approx);
                float w = r.size.width;
                float h = r.size.height;
                if (w <= 0.0f || h <= 0.0f) continue;
                float ratio = std::max(w, h) / std::min(w, h);

                if (ratio <= 12.0f) {
                    double rectArea = w * h;
                    double rectangularity = (rectArea > 0) ? (area / rectArea) : 0.0;
                    if (rectangularity < 0.65) continue; // 矩形度门限

                    // 计算多边形质心及画面中心偏好
                    cv::Point2f polyCenter(0.0f, 0.0f);
                    for (const auto& pt : approx) {
                        polyCenter.x += pt.x;
                        polyCenter.y += pt.y;
                    }
                    polyCenter.x /= 4.0f;
                    polyCenter.y /= 4.0f;

                    float distToCenter = std::hypot(polyCenter.x - frameCenter.x, polyCenter.y - frameCenter.y);
                    float centerPreference = 1.0f - 0.35f * (distToCenter / (frameDiag * 0.5f));
                    centerPreference = std::max(0.35f, std::min(1.0f, centerPreference));

                    // MS Lens 核心原理：内部文本与笔画能量密度评估 (Text & Stroke Saliency)
                    // 空白玻璃车门内部几乎平坦 (meanGrad < 3.0)，而写满车站名与线路的标牌内部充满高频笔画 (meanGrad 15~60)
                    cv::Rect polyBound = cv::boundingRect(approx);
                    int insetX = static_cast<int>(polyBound.width * 0.12f);
                    int insetY = static_cast<int>(polyBound.height * 0.12f);
                    int innerX = std::max(0, polyBound.x + insetX);
                    int innerY = std::max(0, polyBound.y + insetY);
                    int innerW = std::max(1, polyBound.width - 2 * insetX);
                    int innerH = std::max(1, polyBound.height - 2 * insetY);
                    if (innerX + innerW > smallFrame.cols) innerW = smallFrame.cols - innerX;
                    if (innerY + innerH > smallFrame.rows) innerH = smallFrame.rows - innerY;

                    double sumGrad = intGrad.at<double>(innerY + innerH, innerX + innerW)
                                   - intGrad.at<double>(innerY, innerX + innerW)
                                   - intGrad.at<double>(innerY + innerH, innerX)
                                   + intGrad.at<double>(innerY, innerX);
                    double meanGrad = sumGrad / std::max(1, innerW * innerH);

                    // 显著度增益：文本密度越大，显著度权重越高（从 1.0x 最高提升至 4.0x）
                    float textSaliency = 1.0f + 3.0f * std::clamp(static_cast<float>(meanGrad - 3.5) / 16.0f, 0.0f, 1.0f);

                    // 触控焦点偏好 (如果传入了触摸坐标，大幅提权包含触控点的四边形)
                    float touchBonus = 1.0f;
                    if (touchX >= 0.0f && touchY >= 0.0f) {
                        cv::Point2f touchPt(touchX * smallFrame.cols, touchY * smallFrame.rows);
                        if (cv::pointPolygonTest(approx, touchPt, false) >= 0) {
                            touchBonus = 3.0f;
                        }
                    }

                    float areaRatio = static_cast<float>(area / frameArea);
                    // 综合评分：矩形度 * 中心偏好 * (面积自适应平滑) * 文本显著度 * 触控加权
                    double score = rectangularity * centerPreference * (std::sqrt(areaRatio) + 0.18) * textSaliency * touchBonus;

                    if (score > bestScore) {
                        bestScore = score;
                        bestApprox = approx;
                    }
                }
            }
        }
    }

    // 浅色桌面/低对比度边缘断裂自适应 Fallback 探测
    if (bestScore <= 0.0) {
        cv::Mat closeEdges;
        cv::Mat bigKernel = cv::getStructuringElement(cv::MORPH_RECT, cv::Size(11, 11));
        cv::morphologyEx(edges, closeEdges, cv::MORPH_CLOSE, bigKernel);

        std::vector<std::vector<cv::Point>> fallbackContours;
        cv::findContours(closeEdges, fallbackContours, cv::RETR_LIST, cv::CHAIN_APPROX_SIMPLE);
        std::sort(fallbackContours.begin(), fallbackContours.end(), [](const std::vector<cv::Point>& a, const std::vector<cv::Point>& b) {
            return cv::contourArea(a) > cv::contourArea(b);
        });

        for (const auto& contour : fallbackContours) {
            double area = cv::contourArea(contour);
            if (area < 0.05 * frameArea) break;
            if (area > maxArea) continue;

            std::vector<cv::Point> hull;
            cv::convexHull(contour, hull);
            double hullArea = cv::contourArea(hull);
            if (hullArea < 0.05 * frameArea || hullArea > maxArea) continue;

            double hullPerimeter = cv::arcLength(hull, true);
            std::vector<cv::Point> approx;
            for (double epsFactor : {0.02, 0.025, 0.03, 0.04, 0.05, 0.06}) {
                cv::approxPolyDP(hull, approx, epsFactor * hullPerimeter, true);
                if (approx.size() == 4) break;
            }

            if (approx.size() == 4 && cv::isContourConvex(approx)) {
                bool validAngles = true;
                for (int i = 0; i < 4; ++i) {
                    cv::Point p0 = approx[i];
                    cv::Point p1 = approx[(i + 1) % 4];
                    cv::Point p2 = approx[(i + 2) % 4];
                    cv::Point2f v1(static_cast<float>(p0.x - p1.x), static_cast<float>(p0.y - p1.y));
                    cv::Point2f v2(static_cast<float>(p2.x - p1.x), static_cast<float>(p2.y - p1.y));
                    double dot = v1.x * v2.x + v1.y * v2.y;
                    double norm = std::hypot(v1.x, v1.y) * std::hypot(v2.x, v2.y);
                    if (norm > 0) {
                        double cosVal = dot / norm;
                        if (std::abs(cosVal) > 0.85) { // 宽容至 ~32°~148°
                            validAngles = false;
                            break;
                        }
                    }
                }

                if (validAngles) {
                    cv::RotatedRect r = cv::minAreaRect(approx);
                    float w = r.size.width;
                    float h = r.size.height;
                    if (w > 0.0f && h > 0.0f) {
                        float ratio = std::max(w, h) / std::min(w, h);
                        if (ratio <= 12.0f) {
                            double rectArea = w * h;
                            double rectangularity = (rectArea > 0) ? (hullArea / rectArea) : 0.0;
                            if (rectangularity >= 0.65) {
                                bestScore = rectangularity * (hullArea / frameArea);
                                bestApprox = approx;
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    if (bestScore > 0.0 && bestApprox.size() == 4) {
        std::vector<cv::Point2f> approxFloat;
        float invScale = (scale < 1.0f) ? (1.0f / scale) : 1.0f;
        for (const auto& pt : bestApprox) {
            approxFloat.emplace_back(pt.x * invScale, pt.y * invScale);
        }

        result.found = true;
        result.corners = sortCorners(approxFloat);
    }

    return result;
}

std::vector<cv::Point2f> EdgeDetector::sortCorners(const std::vector<cv::Point2f>& points) {
    if (points.size() != 4) return points;

    // 1. 计算 4 角点质心 (Centroid)
    cv::Point2f center(0.0f, 0.0f);
    for (const auto& pt : points) {
        center += pt;
    }
    center.x /= 4.0f;
    center.y /= 4.0f;

    // 2. 按相对于质心的极角升序排序（逆时针或顺时针连续连线，杜绝任何对角交叉）
    struct PolarPoint {
        cv::Point2f pt;
        float angle;
    };

    std::vector<PolarPoint> polarPts;
    polarPts.reserve(4);
    for (const auto& pt : points) {
        float ang = std::atan2(pt.y - center.y, pt.x - center.x);
        polarPts.push_back({pt, ang});
    }

    std::sort(polarPts.begin(), polarPts.end(), [](const PolarPoint& a, const PolarPoint& b) {
        return a.angle < b.angle;
    });

    // 3. 寻找离屏幕坐标原点 (0, 0) 最近的点，作为 Top-Left (左上点)
    int tlIndex = 0;
    float minDistSq = std::numeric_limits<float>::max();
    for (int i = 0; i < 4; ++i) {
        float distSq = polarPts[i].pt.x * polarPts[i].pt.x + polarPts[i].pt.y * polarPts[i].pt.y;
        if (distSq < minDistSq) {
            minDistSq = distSq;
            tlIndex = i;
        }
    }

    // 4. 从左上点开始，沿顺时针方向依次输出: Top-Left, Top-Right, Bottom-Right, Bottom-Left
    // 逆时针排序在屏幕坐标系 (Y向下) 中：angle 递增恰好为顺时针方向
    std::vector<cv::Point2f> sorted(4);
    for (int i = 0; i < 4; ++i) {
        sorted[i] = polarPts[(tlIndex + i) % 4].pt;
    }

    // 验证旋转方向：计算向量叉积判断是否顺时针，如果逆时针则反转
    cv::Point2f v01 = sorted[1] - sorted[0];
    cv::Point2f v03 = sorted[3] - sorted[0];
    float crossProduct = v01.x * v03.y - v01.y * v03.x;
    if (crossProduct < 0) {
        std::swap(sorted[1], sorted[3]);
    }

    return sorted;
}

std::vector<cv::Point> EdgeDetector::findMagneticSnapPoint(const cv::Mat& edgeMat, const cv::Point2f& touchPoint, float radius) {
    std::vector<cv::Point> candidates;
    if (edgeMat.empty() || radius <= 0) return candidates;

    int cx = static_cast<int>(touchPoint.x);
    int cy = static_cast<int>(touchPoint.y);
    int r = static_cast<int>(radius);

    int minX = std::max(0, cx - r);
    int maxX = std::min(edgeMat.cols - 1, cx + r);
    int minY = std::max(0, cy - r);
    int maxY = std::min(edgeMat.rows - 1, cy + r);

    float bestDistSq = radius * radius;
    cv::Point bestPt(-1, -1);

    for (int y = minY; y <= maxY; ++y) {
        const uchar* ptr = edgeMat.ptr<uchar>(y);
        for (int x = minX; x <= maxX; ++x) {
            if (ptr[x] > 180) { // 强边缘点
                float dx = static_cast<float>(x - cx);
                float dy = static_cast<float>(y - cy);
                float distSq = dx * dx + dy * dy;
                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    bestPt = cv::Point(x, y);
                }
            }
        }
    }

    if (bestPt.x != -1) {
        candidates.push_back(bestPt);
    }
    return candidates;
}

float EdgeDetector::findMagneticLineOffset(const cv::Mat& edgeMat, const cv::Point2f& p1, const cv::Point2f& p2, float maxOffset) {
    if (edgeMat.empty() || maxOffset <= 0.0f) {
        return std::numeric_limits<float>::quiet_NaN();
    }

    float dx = p2.x - p1.x;
    float dy = p2.y - p1.y;
    float len = std::hypot(dx, dy);
    if (len < 1e-4f) {
        return std::numeric_limits<float>::quiet_NaN();
    }

    // Line unit normal (nx, ny) corresponding to Ax + By + C = 0 where A = dy/len, B = -dx/len
    float nx = dy / len;
    float ny = -dx / len;

    const int N = 25;
    std::vector<cv::Point2f> samplePoints(N);
    for (int k = 0; k < N; ++k) {
        float t = (k + 0.5f) / static_cast<float>(N);
        samplePoints[k] = cv::Point2f(p1.x + t * dx, p1.y + t * dy);
    }

    int stepCount = static_cast<int>(std::floor(maxOffset));
    if (stepCount < 1) stepCount = 1;

    int totalSteps = 2 * stepCount + 1;
    std::vector<float> scores(totalSteps, 0.0f);
    std::vector<float> deltas(totalSteps, 0.0f);

    float globalMaxScore = 0.0f;

    for (int i = 0; i < totalSteps; ++i) {
        int s = -stepCount + i;
        float delta = static_cast<float>(s);
        deltas[i] = delta;

        float score = 0.0f;
        for (int k = 0; k < N; ++k) {
            float px = samplePoints[k].x + delta * nx;
            float py = samplePoints[k].y + delta * ny;

            int ix = static_cast<int>(std::round(px));
            int iy = static_cast<int>(std::round(py));

            if (ix >= 0 && ix < edgeMat.cols && iy >= 0 && iy < edgeMat.rows) {
                score += edgeMat.ptr<uchar>(iy)[ix];
            }
        }
        scores[i] = score;
        if (score > globalMaxScore) {
            globalMaxScore = score;
        }
    }

    // Peak threshold check per SPEC_05 Rev 2 §3.1: 0.10f * N * 255.0f (sensitive to small targets)
    const float minThreshold = 0.10f * N * 255.0f;
    if (globalMaxScore < minThreshold) {
        return std::numeric_limits<float>::quiet_NaN();
    }

    // Find ridge center (scores within 95% of peak) closest to delta = 0
    float bestOffset = 0.0f;
    float minAbsOffset = std::numeric_limits<float>::max();
    bool foundRidge = false;

    int i = 0;
    while (i < totalSteps) {
        if (scores[i] >= 0.95f * globalMaxScore && scores[i] >= minThreshold) {
            int startIdx = i;
            while (i + 1 < totalSteps && scores[i + 1] >= 0.95f * globalMaxScore) {
                i++;
            }
            int endIdx = i;
            float ridgeOffset = 0.5f * (deltas[startIdx] + deltas[endIdx]);
            float absOffset = std::abs(ridgeOffset);
            if (absOffset < minAbsOffset) {
                minAbsOffset = absOffset;
                bestOffset = ridgeOffset;
                foundRidge = true;
            }
        }
        i++;
    }

    if (foundRidge) {
        return bestOffset;
    }

    return std::numeric_limits<float>::quiet_NaN();
}

std::vector<cv::Point2f> EdgeDetector::findContourAtPoint(const cv::Mat& grayMat, float touchX, float touchY) {
    std::vector<cv::Point2f> result;
    if (grayMat.empty() || touchX < 0 || touchX >= grayMat.cols || touchY < 0 || touchY >= grayMat.rows) {
        return result;
    }

    // 1. 降采样至适中尺寸进行快速轮廓匹配
    int maxDim = std::max(grayMat.cols, grayMat.rows);
    float scale = (maxDim > 640) ? 640.0f / maxDim : 1.0f;
    cv::Mat smallMat;
    if (scale < 1.0f) {
        cv::resize(grayMat, smallMat, cv::Size(), scale, scale, cv::INTER_AREA);
    } else {
        smallMat = grayMat;
    }

    cv::Point2f scaledTouch(touchX * scale, touchY * scale);

    // 2. CLAHE + GaussianBlur + Sensitive Canny (25.0, 70.0) + Close
    cv::Ptr<cv::CLAHE> clahe = cv::createCLAHE(2.0, cv::Size(8, 8));
    cv::Mat claheMat;
    clahe->apply(smallMat, claheMat);

    cv::Mat blurred;
    cv::GaussianBlur(claheMat, blurred, cv::Size(5, 5), 1.0);

    cv::Mat edges;
    cv::Canny(blurred, edges, 25.0, 70.0);

    cv::Mat kernel = cv::getStructuringElement(cv::MORPH_RECT, cv::Size(3, 3));
    cv::morphologyEx(edges, edges, cv::MORPH_CLOSE, kernel);

    // 3. 提取轮廓
    std::vector<std::vector<cv::Point>> contours;
    cv::findContours(edges, contours, cv::RETR_LIST, cv::CHAIN_APPROX_SIMPLE);

    if (contours.empty()) return result;

    double frameArea = smallMat.cols * smallMat.rows;
    double minArea = 0.005 * frameArea;
    double maxArea = 0.98 * frameArea;

    std::vector<cv::Point> bestApprox;
    double bestScore = -1.0;

    for (const auto& contour : contours) {
        double area = cv::contourArea(contour);
        if (area < minArea || area > maxArea) continue;

        cv::Rect bRect = cv::boundingRect(contour);
        // 快速包围盒外剔除
        if (scaledTouch.x < bRect.x - 5 || scaledTouch.x > bRect.x + bRect.width + 5 ||
            scaledTouch.y < bRect.y - 5 || scaledTouch.y > bRect.y + bRect.height + 5) {
            continue;
        }

        double perimeter = cv::arcLength(contour, true);
        std::vector<cv::Point> approx;

        for (double epsFactor : {0.02, 0.025, 0.03, 0.035, 0.04}) {
            cv::approxPolyDP(contour, approx, epsFactor * perimeter, true);
            if (approx.size() == 4) break;
        }

        if (approx.size() != 4) {
            std::vector<cv::Point> hull;
            cv::convexHull(contour, hull);
            double hullPerimeter = cv::arcLength(hull, true);
            for (double epsFactor : {0.02, 0.03, 0.04, 0.05}) {
                cv::approxPolyDP(hull, approx, epsFactor * hullPerimeter, true);
                if (approx.size() == 4) break;
            }
        }

        if (approx.size() == 4 && cv::isContourConvex(approx)) {
            // 确保点击点在凸四边形内部或边界附近
            double dist = cv::pointPolygonTest(approx, scaledTouch, true);
            if (dist < -5.0) continue;

            // 内角校验
            bool validAngles = true;
            for (int i = 0; i < 4; ++i) {
                cv::Point p0 = approx[i];
                cv::Point p1 = approx[(i + 1) % 4];
                cv::Point p2 = approx[(i + 2) % 4];

                cv::Point2f v1(static_cast<float>(p0.x - p1.x), static_cast<float>(p0.y - p1.y));
                cv::Point2f v2(static_cast<float>(p2.x - p1.x), static_cast<float>(p2.y - p1.y));

                double dot = v1.x * v2.x + v1.y * v2.y;
                double norm = std::hypot(v1.x, v1.y) * std::hypot(v2.x, v2.y);
                if (norm > 0) {
                    double cosVal = dot / norm;
                    if (std::abs(cosVal) > 0.77) {
                        validAngles = false;
                        break;
                    }
                }
            }
            if (!validAngles) continue;

            cv::RotatedRect r = cv::minAreaRect(approx);
            float w = r.size.width;
            float h = r.size.height;
            if (w <= 0.0f || h <= 0.0f) continue;
            float ratio = std::max(w, h) / std::min(w, h);
            if (ratio > 12.0f) continue;

            double rectArea = w * h;
            double rectangularity = (rectArea > 0) ? (area / rectArea) : 0.0;
            if (rectangularity < 0.65) continue;

            // 优先匹配包含触摸点且形状最规整的目标
            double areaRatio = area / frameArea;
            double score = rectangularity / (std::sqrt(areaRatio) + 0.05);

            if (score > bestScore) {
                bestScore = score;
                bestApprox = approx;
            }
        }
    }

    if (bestScore > 0.0 && bestApprox.size() == 4) {
        std::vector<cv::Point2f> approxFloat;
        float invScale = (scale < 1.0f) ? (1.0f / scale) : 1.0f;
        for (const auto& pt : bestApprox) {
            approxFloat.emplace_back(pt.x * invScale, pt.y * invScale);
        }
        result = sortCorners(approxFloat);
    }

    return result;
}

EdgeDetector::StructuralLinesResult EdgeDetector::detectStructuralLines(const cv::Mat& grayImage, float origWidth, float origHeight) {
    StructuralLinesResult result;
    if (grayImage.empty()) {
        return result;
    }

    float origW = (origWidth > 0.0f) ? origWidth : static_cast<float>(grayImage.cols);
    float origH = (origHeight > 0.0f) ? origHeight : static_cast<float>(grayImage.rows);

    float maxDim = std::max(grayImage.cols, grayImage.rows);
    float scale = 1.0f;
    cv::Mat lsdInput;

    if (maxDim > 1024.0f) {
        scale = 1024.0f / maxDim;
        cv::resize(grayImage, lsdInput, cv::Size(), scale, scale, cv::INTER_AREA);
    } else {
        lsdInput = grayImage;
    }

    cv::Ptr<cv::LineSegmentDetector> lsd = cv::createLineSegmentDetector(cv::LSD_REFINE_STD);
    std::vector<cv::Vec4f> detectedLines;
    lsd->detect(lsdInput, detectedLines);

    // 将 lsdInput 坐标系统直接映射至原图坐标系 (origW, origH)
    float scaleX = origW / static_cast<float>(lsdInput.cols);
    float scaleY = origH / static_cast<float>(lsdInput.rows);
    float minLength = 0.015f * std::min(origW, origH);

    for (const auto& line : detectedLines) {
        float x1 = line[0] * scaleX;
        float y1 = line[1] * scaleY;
        float x2 = line[2] * scaleX;
        float y2 = line[3] * scaleY;

        float dx = std::abs(x2 - x1);
        float dy = std::abs(y2 - y1);
        float len = std::hypot(dx, dy);

        if (len < minLength) {
            continue;
        }

        if (dx >= dy) {
            // Horizontal line (|dx| >= |dy|, angle <= 45 deg)
            result.horizontalLines.push_back(x1);
            result.horizontalLines.push_back(y1);
            result.horizontalLines.push_back(x2);
            result.horizontalLines.push_back(y2);
        } else {
            // Vertical line (|dy| > |dx|, angle > 45 deg)
            result.verticalLines.push_back(x1);
            result.verticalLines.push_back(y1);
            result.verticalLines.push_back(x2);
            result.verticalLines.push_back(y2);
        }
    }

    // Invariant: Append the 4 image boundaries (SPEC_06 §2.2)
    // Top border: [0, 0, W, 0]
    result.horizontalLines.push_back(0.0f);
    result.horizontalLines.push_back(0.0f);
    result.horizontalLines.push_back(origW);
    result.horizontalLines.push_back(0.0f);

    // Bottom border: [0, H, W, H]
    result.horizontalLines.push_back(0.0f);
    result.horizontalLines.push_back(origH);
    result.horizontalLines.push_back(origW);
    result.horizontalLines.push_back(origH);

    // Left border: [0, 0, 0, H]
    result.verticalLines.push_back(0.0f);
    result.verticalLines.push_back(0.0f);
    result.verticalLines.push_back(0.0f);
    result.verticalLines.push_back(origH);

    // Right border: [W, 0, W, H]
    result.verticalLines.push_back(origW);
    result.verticalLines.push_back(0.0f);
    result.verticalLines.push_back(origW);
    result.verticalLines.push_back(origH);

    return result;
}


