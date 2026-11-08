package com.flowgate.gateway.quota;
import com.flowgate.contract.SnapshotCodec;
import com.flowgate.gateway.filter.RequestIdWebFilter;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
/** 准入拒绝不是下游故障；官方CB忽略此异常，错误边界保留真实403/429/503。 */
public final class QuotaDenied extends RuntimeException {
 public final int status;public final String code;
 public QuotaDenied(int status,String code,String message){super(message);this.status=status;this.code=code;}
 public Mono<Void> write(ServerWebExchange exchange){
  if(exchange.getResponse().isCommitted())return Mono.empty();var response=exchange.getResponse();response.setStatusCode(HttpStatusCode.valueOf(status));
  var headers=response.getHeaders();headers.remove(HttpHeaders.CONTENT_LENGTH);headers.remove(HttpHeaders.CONTENT_ENCODING);headers.remove(HttpHeaders.TRANSFER_ENCODING);headers.setContentType(MediaType.APPLICATION_JSON);
  return response.writeWith(Mono.just(response.bufferFactory().wrap(SnapshotCodec.JSON.writeValueAsBytes(Map.of("code",code,"message",getMessage(),"requestId",exchange.getAttributeOrDefault(RequestIdWebFilter.REQUEST_ID,""),"source","QUOTA_ADMISSION")))));
 }
}
