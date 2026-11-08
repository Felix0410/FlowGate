package com.flowgate.control.async;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
/** 完整三元组稳定游标，白名单解析且拒绝非规范或溢出输入。 */
public record UsageCursor(String day,long applicationId,long apiId) {
 public String encode(){return Base64.getUrlEncoder().withoutPadding().encodeToString((day+"|"+applicationId+"|"+apiId).getBytes(StandardCharsets.UTF_8));}
 public static UsageCursor parse(String encoded){
  if(encoded==null||encoded.isBlank())return null;
  try{if(encoded.length()>128)throw new IllegalArgumentException();String value=new String(Base64.getUrlDecoder().decode(encoded),StandardCharsets.UTF_8);if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}\\|[1-9][0-9]{0,18}\\|[1-9][0-9]{0,18}"))throw new IllegalArgumentException();var parts=value.split("\\|");LocalDate.parse(parts[0]);var cursor=new UsageCursor(parts[0],Long.parseLong(parts[1]),Long.parseLong(parts[2]));if(!cursor.encode().equals(encoded))throw new IllegalArgumentException();return cursor;}catch(RuntimeException invalid){throw new IllegalArgumentException("复合游标非法");}
 }
}
