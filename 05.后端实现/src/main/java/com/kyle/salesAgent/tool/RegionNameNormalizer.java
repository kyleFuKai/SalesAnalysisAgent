package com.kyle.salesAgent.tool;

/**
 * 统一处理模型传入的大区名称，避免把字符串 "null" 当成真实大区查询。
 */
final class RegionNameNormalizer {

    private RegionNameNormalizer() {
    }

    /**
     * 未指定大区时返回 null；其他名称去掉首尾空格后原样保留，交给查询逻辑校验。
     *
     * @param regionName 模型传入的大区名称
     * @return 规范化后的大区名称，或表示全公司的 null
     */
    static String normalize(String regionName) {
        if (regionName == null || regionName.isBlank()) {
            return null;
        }
        String normalized = regionName.trim();
        return "null".equalsIgnoreCase(normalized) ? null : normalized;
    }
}
