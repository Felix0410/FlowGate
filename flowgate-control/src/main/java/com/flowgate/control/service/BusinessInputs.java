package com.flowgate.control.service;

import com.flowgate.control.security.ApiFailure;
import java.time.Instant;
import java.util.*;

/** V11管理域的明确输入边界；不复用旧Caller认证或隐式修改内部字段。 */
public final class BusinessInputs {
    private BusinessInputs() {}
    public static void require(boolean condition,String code,String message) { if(!condition) throw new ApiFailure(400,code,message); }
    public static void fields(Map<String,Object> input,String... allowed) { require(input!=null && !input.isEmpty() && Set.of(allowed).containsAll(input.keySet()),"VALIDATION_ERROR","字段为空或包含不可修改字段"); }
    public static String text(Object value,String name,int max) { require(value instanceof String,"VALIDATION_ERROR",name+"必填"); String text=((String)value).trim();require(!text.isEmpty() && text.length()<=max,"VALIDATION_ERROR",name+"长度不合法");return text; }
    public static String code(Object value) { String code=text(value,"代码",100);require(code.matches("[A-Za-z0-9_-]+"),"VALIDATION_ERROR","代码仅支持字母数字横线下划线");return code; }
    public static String status(Object value) { String status=text(value,"状态",16);require(Set.of("ACTIVE","SUSPENDED").contains(status),"VALIDATION_ERROR","状态只能ACTIVE或SUSPENDED");return status; }
    public static long number(Object value,long max,String label) {
        require(value instanceof Number,"VALIDATION_ERROR",label+"必须为整数");
        try { long result=new java.math.BigDecimal(value.toString()).longValueExact();require(result>0 && result<=max,"VALIDATION_ERROR",label+"超出允许范围");return result; }
        catch(ArithmeticException|NumberFormatException invalid) { throw new ApiFailure(400,"VALIDATION_ERROR",label+"必须为有效整数"); }
    }
    public static long id(long value) { require(value>0,"VALIDATION_ERROR","ID必须为正整数");return value; }
    public static void page(long after,int limit) { require(after>=0 && limit>=1 && limit<=100,"VALIDATION_ERROR","游标非负，页大小须1..100"); }
    public static Instant instant(Object value) {
        require(value instanceof String && ((String)value).endsWith("Z"),"INVALID_PERIOD","日期必须为ISO8601 UTC时间并以Z结尾");
        try {
            Instant result=Instant.parse((String)value);
            require(result.getNano()%1_000_000==0 && !result.isBefore(Instant.EPOCH) && result.isBefore(Instant.parse("2038-01-19T03:14:07Z")),"INVALID_PERIOD","日期须为毫秒精度且处于MySQL TIMESTAMP支持范围");return result;
        } catch(java.time.format.DateTimeParseException invalid) { throw new ApiFailure(400,"INVALID_PERIOD","UTC日期不合法"); }
    }
    public static ApiFailure missing(String name) { return new ApiFailure(404,"NOT_FOUND",name+"不存在"); }
    public static ApiFailure duplicate() { return new ApiFailure(409,"DUPLICATE_CODE","代码已存在，请编辑原记录或使用不同代码"); }
}
