#pragma once

#include <vulkan/vulkan.h>
#include <opencv2/core.hpp>
#include <vector>
#include <mutex>

class VulkanComputeEngine {
public:
    static VulkanComputeEngine& getInstance();

    // Check if Vulkan instance and compute pipeline are ready on device
    bool isSupported();

    // Execute GPU-accelerated HDR highlight grafting, local tone mapping, and S-curve toe
    bool processHdrLtm(
        const cv::Mat& inBaseRgba,
        const cv::Mat& inShortRgba,
        cv::Mat& outRgba,
        float exposureGain = 4.0f,
        float ltmMu = 8.0f,
        float ltmBeta = 1.15f,
        float toeThreshold = 28.0f,
        float toePower = 0.65f,
        bool isScreenMode = true
    );

private:
    VulkanComputeEngine();
    ~VulkanComputeEngine();

    VulkanComputeEngine(const VulkanComputeEngine&) = delete;
    VulkanComputeEngine& operator=(const VulkanComputeEngine&) = delete;

    bool initVulkan();
    void cleanup();

    uint32_t findMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags properties);
    bool createBuffer(VkDeviceSize size, VkBufferUsageFlags usage, VkMemoryPropertyFlags properties, VkBuffer& buffer, VkDeviceMemory& bufferMemory);

    std::mutex m_mutex;
    bool m_initialized = false;
    bool m_supported = false;

    VkInstance m_instance = VK_NULL_HANDLE;
    VkPhysicalDevice m_physicalDevice = VK_NULL_HANDLE;
    VkDevice m_device = VK_NULL_HANDLE;
    VkQueue m_computeQueue = VK_NULL_HANDLE;
    uint32_t m_computeQueueFamilyIndex = 0;

    VkCommandPool m_commandPool = VK_NULL_HANDLE;
    VkDescriptorSetLayout m_descriptorSetLayout = VK_NULL_HANDLE;
    VkPipelineLayout m_pipelineLayout = VK_NULL_HANDLE;
    VkPipeline m_pipeline = VK_NULL_HANDLE;
    VkShaderModule m_shaderModule = VK_NULL_HANDLE;
};
