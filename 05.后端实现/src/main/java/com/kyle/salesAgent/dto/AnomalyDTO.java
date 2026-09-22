package com.kyle.salesAgent.dto;

/**
 * 异常检测结果 DTO，AnomalyDetectionTool 四项检测的统一产出格式。
 * 工具把它格式化成文字给模型，未来如果前端要做异常卡片，也是这份结构。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/9/21 23:32
 */
public record AnomalyDTO(
        /** 异常类型，如：大区订单量骤降 / 产品连续零销售 / 销售员退单率异常 / 销售员业绩骤降 */
        String type,
        /** 严重度：HIGH / MEDIUM / LOW（输出时映射为 🔴🟡🔵） */
        String severity,
        /** 异常主体（大区名/产品名/销售员名） */
        String subject,
        /** 异常描述，带具体数字和判定依据 */
        String description,
        /** 处理建议 */
        String suggestion
) {}
