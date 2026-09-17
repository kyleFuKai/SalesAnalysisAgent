package com.kyle.salesAgent.dto;

import java.math.BigDecimal;

/**
 * 销售员业绩汇总 DTO
 * <p>用于个人业绩查询与销售员排名场景，如"本月 Top 5 销售员"、"张伟这个月卖了多少"
 *
 * @author kyle
 * @version 1.0
 * @date 2026/9/17 23:40
 */
public record RepSalesDTO(
        /** 销售员ID */
        Long repId,
        /** 销售员姓名 */
        String repName,
        /** 所属大区ID */
        Long regionId,
        /** 所属大区名称 */
        String regionName,
        /** 销售总额（毛额口径，仅 COMPLETED 订单） */
        BigDecimal totalAmount,
        /** 订单笔数 */
        Integer orderCount
) {}
