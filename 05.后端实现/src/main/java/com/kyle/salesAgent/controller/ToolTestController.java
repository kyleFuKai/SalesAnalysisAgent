package com.kyle.salesAgent.controller;

import com.kyle.salesAgent.tool.SalesQueryTool;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/test/tool")
public class ToolTestController {

    private final SalesQueryTool salesQueryTool;

    public ToolTestController(SalesQueryTool salesQueryTool) {
        this.salesQueryTool = salesQueryTool;
    }

    record QueryRequest(String startDate, String endDate,
                        String regionName, String repName, int limit) {}

    @PostMapping("/query-orders")
    public String queryOrders(@RequestBody QueryRequest req) {
        return salesQueryTool.queryOrders(
                req.startDate(), req.endDate(), req.regionName(), req.repName(), req.limit());
    }
}