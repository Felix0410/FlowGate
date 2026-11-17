export function money(cents){return '¥'+(Number(cents)/100).toFixed(2);}
export function orderStatus(status){return ({PENDING_PAYMENT:'待模拟付款',PAID_PENDING_FULFILLMENT:'已模拟付款，等待权益',FULFILLMENT_RETRY:'权益开通重试中',FULFILLMENT_REVIEW:'权益过期，待运营检查',FULFILLED:'权益已开通',REFUNDED:'已模拟退款',CANCELLED:'已取消',CLOSED_TIMEOUT:'超时关闭'})[status]||status||'—';}
export function skuPeriod(policy,now=new Date()){return new Date(Date.UTC(now.getUTCFullYear(),now.getUTCMonth()+(policy==='NEXT_MONTH'?1:0),1)).toISOString().slice(0,7).replace('-','');}
