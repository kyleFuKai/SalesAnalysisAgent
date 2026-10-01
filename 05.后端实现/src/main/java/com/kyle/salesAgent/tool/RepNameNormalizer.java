package com.kyle.salesAgent.tool;

/**
 * 统一处理模型传入的销售员姓名，避免把字符串 "null" 当成真实姓名查询。
 */
final class RepNameNormalizer {

    private RepNameNormalizer() {
    }

    /**
     * 未指定销售员时返回 null；其他姓名去掉首尾空格后原样保留，交给查询逻辑校验。
     *
     * @param repName 模型传入的销售员姓名
     * @return 规范化后的姓名，或表示未指定销售员的 null
     */
    static String normalize(String repName) {
        if (repName == null || repName.isBlank()) {
            return null;
        }
        String normalized = repName.trim();
        return "null".equalsIgnoreCase(normalized) ? null : normalized;
    }
}
