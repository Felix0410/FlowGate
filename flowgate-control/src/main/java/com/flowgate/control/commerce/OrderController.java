package com.flowgate.control.commerce;

import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/portal/api/orders")
public class OrderController {
    private final OrderService orders;private final SimulatedRefundService refunds;
    public OrderController(OrderService orders,SimulatedRefundService refunds){this.orders=orders;this.refunds=refunds;}
    private long user(HttpServletRequest r){return (Long)r.getAttribute("portalUser");}
    @PostMapping public Object create(@RequestBody Map<String,Object> body,HttpServletRequest r){var result=orders.create(user(r),body);return ResponseEntity.status(result.created()?201:200).body(result.order());}
    @GetMapping public Object list(@RequestParam(defaultValue="0")long afterId,@RequestParam(defaultValue="20")int limit,HttpServletRequest r){return orders.list(user(r),afterId,limit);}
    @GetMapping("/{id}") public Object detail(@PathVariable String id,HttpServletRequest r){return orders.detail(user(r),id);}
    @PostMapping("/{id}/simulate-pay") public Object pay(@PathVariable String id,HttpServletRequest r){return orders.pay(user(r),id);}
    @PostMapping("/{id}/cancel") public Object cancel(@PathVariable String id,HttpServletRequest r){return orders.cancel(user(r),id);}
    @PostMapping("/{id}/simulate-refund") public Object refund(@PathVariable String id,HttpServletRequest r){return refunds.refund(user(r),id);}
}
