#include "vulkan_compute_engine.h"
#include "shaders/vulkan_hdr_ltm_spv.h"
#include <android/log.h>
#include <cstring>

#define LOG_TAG "HachiCam-Vulkan"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct PushConstants {
    int width;
    int height;
    float exposureGain;
    float ltmMu;
    float ltmBeta;
    float toeThreshold;
    float toePower;
    int isScreenMode;
};

VulkanComputeEngine& VulkanComputeEngine::getInstance() {
    static VulkanComputeEngine instance;
    return instance;
}

VulkanComputeEngine::VulkanComputeEngine() {
    m_supported = initVulkan();
}

VulkanComputeEngine::~VulkanComputeEngine() {
    cleanup();
}

bool VulkanComputeEngine::isSupported() {
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_supported;
}

bool VulkanComputeEngine::initVulkan() {
    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "HachiCam";
    appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.pEngineName = "HachiComputeEngine";
    appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.apiVersion = VK_API_VERSION_1_1;

    VkInstanceCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &appInfo;

    VkResult res = vkCreateInstance(&createInfo, nullptr, &m_instance);
    if (res != VK_SUCCESS || m_instance == VK_NULL_HANDLE) {
        LOGW("Vulkan instance creation failed: res=%d", res);
        return false;
    }

    uint32_t deviceCount = 0;
    vkEnumeratePhysicalDevices(m_instance, &deviceCount, nullptr);
    if (deviceCount == 0) {
        LOGW("No Vulkan physical devices found");
        return false;
    }

    std::vector<VkPhysicalDevice> devices(deviceCount);
    vkEnumeratePhysicalDevices(m_instance, &deviceCount, devices.data());

    // Select device with compute queue
    for (const auto& dev : devices) {
        uint32_t queueFamilyCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(dev, &queueFamilyCount, nullptr);
        std::vector<VkQueueFamilyProperties> queueFamilies(queueFamilyCount);
        vkGetPhysicalDeviceQueueFamilyProperties(dev, &queueFamilyCount, queueFamilies.data());

        for (uint32_t i = 0; i < queueFamilyCount; ++i) {
            if (queueFamilies[i].queueFlags & VK_QUEUE_COMPUTE_BIT) {
                m_physicalDevice = dev;
                m_computeQueueFamilyIndex = i;
                break;
            }
        }
        if (m_physicalDevice != VK_NULL_HANDLE) break;
    }

    if (m_physicalDevice == VK_NULL_HANDLE) {
        LOGW("No physical device with compute queue found");
        return false;
    }

    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(m_physicalDevice, &props);
    LOGI("Selected Vulkan GPU: %s (Type: %d, API: %u.%u.%u)",
         props.deviceName, props.deviceType,
         VK_VERSION_MAJOR(props.apiVersion),
         VK_VERSION_MINOR(props.apiVersion),
         VK_VERSION_PATCH(props.apiVersion));

    float queuePriority = 1.0f;
    VkDeviceQueueCreateInfo queueCreateInfo{};
    queueCreateInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueCreateInfo.queueFamilyIndex = m_computeQueueFamilyIndex;
    queueCreateInfo.queueCount = 1;
    queueCreateInfo.pQueuePriorities = &queuePriority;

    VkDeviceCreateInfo deviceCreateInfo{};
    deviceCreateInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    deviceCreateInfo.queueCreateInfoCount = 1;
    deviceCreateInfo.pQueueCreateInfos = &queueCreateInfo;

    res = vkCreateDevice(m_physicalDevice, &deviceCreateInfo, nullptr, &m_device);
    if (res != VK_SUCCESS || m_device == VK_NULL_HANDLE) {
        LOGE("Failed to create Vulkan logical device: res=%d", res);
        return false;
    }

    vkGetDeviceQueue(m_device, m_computeQueueFamilyIndex, 0, &m_computeQueue);

    // Create command pool
    VkCommandPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    poolInfo.queueFamilyIndex = m_computeQueueFamilyIndex;
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;

    res = vkCreateCommandPool(m_device, &poolInfo, nullptr, &m_commandPool);
    if (res != VK_SUCCESS) {
        LOGE("Failed to create Vulkan command pool");
        return false;
    }

    // Create shader module from embedded SPIR-V
    VkShaderModuleCreateInfo shaderInfo{};
    shaderInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    shaderInfo.codeSize = vulkan_hdr_ltm_spv_size;
    shaderInfo.pCode = vulkan_hdr_ltm_spv;

    res = vkCreateShaderModule(m_device, &shaderInfo, nullptr, &m_shaderModule);
    if (res != VK_SUCCESS) {
        LOGE("Failed to create Vulkan compute shader module");
        return false;
    }

    // Descriptor set layout (3 storage buffers)
    VkDescriptorSetLayoutBinding bindings[3]{};
    for (int i = 0; i < 3; ++i) {
        bindings[i].binding = i;
        bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        bindings[i].descriptorCount = 1;
        bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }

    VkDescriptorSetLayoutCreateInfo layoutInfo{};
    layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    layoutInfo.bindingCount = 3;
    layoutInfo.pBindings = bindings;

    res = vkCreateDescriptorSetLayout(m_device, &layoutInfo, nullptr, &m_descriptorSetLayout);
    if (res != VK_SUCCESS) {
        LOGE("Failed to create descriptor set layout");
        return false;
    }

    // Push constant range
    VkPushConstantRange pushRange{};
    pushRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pushRange.offset = 0;
    pushRange.size = sizeof(PushConstants);

    VkPipelineLayoutCreateInfo pipelineLayoutInfo{};
    pipelineLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipelineLayoutInfo.setLayoutCount = 1;
    pipelineLayoutInfo.pSetLayouts = &m_descriptorSetLayout;
    pipelineLayoutInfo.pushConstantRangeCount = 1;
    pipelineLayoutInfo.pPushConstantRanges = &pushRange;

    res = vkCreatePipelineLayout(m_device, &pipelineLayoutInfo, nullptr, &m_pipelineLayout);
    if (res != VK_SUCCESS) {
        LOGE("Failed to create pipeline layout");
        return false;
    }

    // Compute pipeline
    VkComputePipelineCreateInfo computePipelineInfo{};
    computePipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    computePipelineInfo.layout = m_pipelineLayout;
    computePipelineInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    computePipelineInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    computePipelineInfo.stage.module = m_shaderModule;
    computePipelineInfo.stage.pName = "main";

    res = vkCreateComputePipelines(m_device, VK_NULL_HANDLE, 1, &computePipelineInfo, nullptr, &m_pipeline);
    if (res != VK_SUCCESS) {
        LOGE("Failed to create Vulkan compute pipeline");
        return false;
    }

    m_initialized = true;
    LOGI("Vulkan Compute Engine successfully initialized and armed!");
    return true;
}

uint32_t VulkanComputeEngine::findMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags properties) {
    VkPhysicalDeviceMemoryProperties memProperties;
    vkGetPhysicalDeviceMemoryProperties(m_physicalDevice, &memProperties);

    for (uint32_t i = 0; i < memProperties.memoryTypeCount; i++) {
        if ((typeFilter & (1 << i)) &&
            (memProperties.memoryTypes[i].propertyFlags & properties) == properties) {
            return i;
        }
    }
    return 0;
}

bool VulkanComputeEngine::createBuffer(
    VkDeviceSize size,
    VkBufferUsageFlags usage,
    VkMemoryPropertyFlags properties,
    VkBuffer& buffer,
    VkDeviceMemory& bufferMemory
) {
    VkBufferCreateInfo bufferInfo{};
    bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufferInfo.size = size;
    bufferInfo.usage = usage;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    if (vkCreateBuffer(m_device, &bufferInfo, nullptr, &buffer) != VK_SUCCESS) {
        return false;
    }

    VkMemoryRequirements memRequirements;
    vkGetBufferMemoryRequirements(m_device, buffer, &memRequirements);

    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memRequirements.size;
    allocInfo.memoryTypeIndex = findMemoryType(memRequirements.memoryTypeBits, properties);

    if (vkAllocateMemory(m_device, &allocInfo, nullptr, &bufferMemory) != VK_SUCCESS) {
        vkDestroyBuffer(m_device, buffer, nullptr);
        return false;
    }

    vkBindBufferMemory(m_device, buffer, bufferMemory, 0);
    return true;
}

bool VulkanComputeEngine::processHdrLtm(
    const cv::Mat& inBaseRgba,
    const cv::Mat& inShortRgba,
    cv::Mat& outRgba,
    float exposureGain,
    float ltmMu,
    float ltmBeta,
    float toeThreshold,
    float toePower,
    bool isScreenMode
) {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (!m_initialized) return false;

    int width = inBaseRgba.cols;
    int height = inBaseRgba.rows;
    if (width <= 0 || height <= 0) return false;

    VkDeviceSize imageSize = width * height * 4; // RGBA8

    VkBuffer inBaseBuffer, inShortBuffer, outBuffer;
    VkDeviceMemory inBaseMem, inShortMem, outMem;

    VkMemoryPropertyFlags memFlags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;

    if (!createBuffer(imageSize, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, memFlags, inBaseBuffer, inBaseMem)) {
        return false;
    }
    if (!createBuffer(imageSize, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, memFlags, inShortBuffer, inShortMem)) {
        vkDestroyBuffer(m_device, inBaseBuffer, nullptr);
        vkFreeMemory(m_device, inBaseMem, nullptr);
        return false;
    }
    if (!createBuffer(imageSize, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, memFlags, outBuffer, outMem)) {
        vkDestroyBuffer(m_device, inBaseBuffer, nullptr);
        vkFreeMemory(m_device, inBaseMem, nullptr);
        vkDestroyBuffer(m_device, inShortBuffer, nullptr);
        vkFreeMemory(m_device, inShortMem, nullptr);
        return false;
    }

    // Upload host data to mapped memory
    void* data;
    vkMapMemory(m_device, inBaseMem, 0, imageSize, 0, &data);
    std::memcpy(data, inBaseRgba.data, imageSize);
    vkUnmapMemory(m_device, inBaseMem);

    vkMapMemory(m_device, inShortMem, 0, imageSize, 0, &data);
    std::memcpy(data, inShortRgba.data, imageSize);
    vkUnmapMemory(m_device, inShortMem);

    // Create transient descriptor pool
    VkDescriptorPoolSize poolSize{};
    poolSize.type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSize.descriptorCount = 3;

    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.poolSizeCount = 1;
    poolInfo.pPoolSizes = &poolSize;
    poolInfo.maxSets = 1;

    VkDescriptorPool descriptorPool;
    if (vkCreateDescriptorPool(m_device, &poolInfo, nullptr, &descriptorPool) != VK_SUCCESS) {
        // Cleanup buffers
        vkDestroyBuffer(m_device, inBaseBuffer, nullptr);
        vkFreeMemory(m_device, inBaseMem, nullptr);
        vkDestroyBuffer(m_device, inShortBuffer, nullptr);
        vkFreeMemory(m_device, inShortMem, nullptr);
        vkDestroyBuffer(m_device, outBuffer, nullptr);
        vkFreeMemory(m_device, outMem, nullptr);
        return false;
    }

    VkDescriptorSetAllocateInfo setAllocInfo{};
    setAllocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    setAllocInfo.descriptorPool = descriptorPool;
    setAllocInfo.descriptorSetCount = 1;
    setAllocInfo.pSetLayouts = &m_descriptorSetLayout;

    VkDescriptorSet descriptorSet;
    vkAllocateDescriptorSets(m_device, &setAllocInfo, &descriptorSet);

    VkDescriptorBufferInfo bufferInfos[3]{};
    bufferInfos[0].buffer = inBaseBuffer;
    bufferInfos[0].offset = 0;
    bufferInfos[0].range = imageSize;

    bufferInfos[1].buffer = inShortBuffer;
    bufferInfos[1].offset = 0;
    bufferInfos[1].range = imageSize;

    bufferInfos[2].buffer = outBuffer;
    bufferInfos[2].offset = 0;
    bufferInfos[2].range = imageSize;

    VkWriteDescriptorSet descriptorWrites[3]{};
    for (int i = 0; i < 3; ++i) {
        descriptorWrites[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        descriptorWrites[i].dstSet = descriptorSet;
        descriptorWrites[i].dstBinding = i;
        descriptorWrites[i].dstArrayElement = 0;
        descriptorWrites[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        descriptorWrites[i].descriptorCount = 1;
        descriptorWrites[i].pBufferInfo = &bufferInfos[i];
    }
    vkUpdateDescriptorSets(m_device, 3, descriptorWrites, 0, nullptr);

    // Allocate command buffer
    VkCommandBufferAllocateInfo cmdAllocInfo{};
    cmdAllocInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cmdAllocInfo.commandPool = m_commandPool;
    cmdAllocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cmdAllocInfo.commandBufferCount = 1;

    VkCommandBuffer cmd;
    vkAllocateCommandBuffers(m_device, &cmdAllocInfo, &cmd);

    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;

    vkBeginCommandBuffer(cmd, &beginInfo);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, m_pipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, m_pipelineLayout, 0, 1, &descriptorSet, 0, nullptr);

    PushConstants push{};
    push.width = width;
    push.height = height;
    push.exposureGain = exposureGain;
    push.ltmMu = ltmMu;
    push.ltmBeta = ltmBeta;
    push.toeThreshold = toeThreshold;
    push.toePower = toePower;
    push.isScreenMode = isScreenMode ? 1 : 0;

    vkCmdPushConstants(cmd, m_pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(push), &push);

    uint32_t groupX = (width + 15) / 16;
    uint32_t groupY = (height + 15) / 16;
    vkCmdDispatch(cmd, groupX, groupY, 1);

    vkEndCommandBuffer(cmd);

    VkSubmitInfo submitInfo{};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &cmd;

    vkQueueSubmit(m_computeQueue, 1, &submitInfo, VK_NULL_HANDLE);
    vkQueueWaitIdle(m_computeQueue);

    // Readback output
    outRgba.create(height, width, CV_8UC4);
    vkMapMemory(m_device, outMem, 0, imageSize, 0, &data);
    std::memcpy(outRgba.data, data, imageSize);
    vkUnmapMemory(m_device, outMem);

    // Cleanup transient resources
    vkFreeCommandBuffers(m_device, m_commandPool, 1, &cmd);
    vkDestroyDescriptorPool(m_device, descriptorPool, nullptr);
    vkDestroyBuffer(m_device, inBaseBuffer, nullptr);
    vkFreeMemory(m_device, inBaseMem, nullptr);
    vkDestroyBuffer(m_device, inShortBuffer, nullptr);
    vkFreeMemory(m_device, inShortMem, nullptr);
    vkDestroyBuffer(m_device, outBuffer, nullptr);
    vkFreeMemory(m_device, outMem, nullptr);

    return true;
}

void VulkanComputeEngine::cleanup() {
    if (m_device != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(m_device);
        if (m_pipeline != VK_NULL_HANDLE) vkDestroyPipeline(m_device, m_pipeline, nullptr);
        if (m_pipelineLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(m_device, m_pipelineLayout, nullptr);
        if (m_descriptorSetLayout != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(m_device, m_descriptorSetLayout, nullptr);
        if (m_shaderModule != VK_NULL_HANDLE) vkDestroyShaderModule(m_device, m_shaderModule, nullptr);
        if (m_commandPool != VK_NULL_HANDLE) vkDestroyCommandPool(m_device, m_commandPool, nullptr);
        vkDestroyDevice(m_device, nullptr);
        m_device = VK_NULL_HANDLE;
    }
    if (m_instance != VK_NULL_HANDLE) {
        vkDestroyInstance(m_instance, nullptr);
        m_instance = VK_NULL_HANDLE;
    }
    m_initialized = false;
    m_supported = false;
}
