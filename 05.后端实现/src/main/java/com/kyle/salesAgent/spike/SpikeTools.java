package com.kyle.salesAgent.spike;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * spike 专用模拟工具，长得和正式工具一样但返回写死的数据。
 * spike 只关心模型会不会选对工具、传对参数，数据真假无所谓。
 * 用完即删，不入正式包。
 */
@Slf4j
public class SpikeTools {

    /** 模型实际调过哪些工具，按顺序记着，测试端点读它做断言。
     *  static 共享：两个助手各 new 了一份工具实例，但都记到这里；
     *  并发请求会互相污染，spike 单线程 curl 无所谓，别抄到正式代码里。 */
    public static final List<String> INVOKED = Collections.synchronizedList(new ArrayList<>());

    @Tool("查询指定大区在指定年月的销售总额")
    public String queryRegionSales(
            @P("大区名称，如：华东区") String regionName,
            @P("年份，如 2026") int year,
            @P("月份，1-12") int month) {
        INVOKED.add("queryRegionSales");
        log.info("[SPIKE] 调用 queryRegionSales: {} {}-{}", regionName, year, month);
        return regionName + " " + year + " 年 " + month + " 月销售额 ¥1,258,000（spike 模拟数据）";
    }

    @Tool("查询指定销售员在指定年月的个人销售总额")
    public String queryRepSales(
            @P("销售员姓名，如：张伟") String repName,
            @P("年份，如 2026") int year,
            @P("月份，1-12") int month) {
        INVOKED.add("queryRepSales");
        log.info("[SPIKE] 调用 queryRepSales: {} {}-{}", repName, year, month);
        return repName + " " + year + " 年 " + month + " 月个人销售额 ¥356,000（spike 模拟数据）";
    }
}
