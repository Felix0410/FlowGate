package com.flowgate.control.async;

import java.util.*;
import java.sql.SQLException;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.springframework.dao.DataAccessException;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.listener.*;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.util.backoff.*;
import com.flowgate.control.security.ApiFailure;

/** 数据库故障有界退避自动重试；永久毒消息仅暂停问题分区，二者均不提前ACK。 */
public class UsageConsumerErrorHandler implements CommonErrorHandler {
    private final DefaultErrorHandler transientErrors;
    private final AsyncDiagnostics diagnostics;
    public UsageConsumerErrorHandler(AsyncDiagnostics diagnostics){this(diagnostics,backoff());}
    UsageConsumerErrorHandler(AsyncDiagnostics diagnostics,BackOff backoff){
        this.diagnostics=diagnostics;
        transientErrors=new DefaultErrorHandler((record,error)->{throw new IllegalStateException("TRANSIENT_RETRY_NOT_RECOVERED");},backoff);
        transientErrors.setClassifications(Map.of(Exception.class,true),true);
        transientErrors.setResetStateOnExceptionChange(false);
        transientErrors.setAckAfterHandle(false);transientErrors.setCommitRecovered(false);
        transientErrors.setLogLevel(KafkaException.Level.DEBUG);
        transientErrors.setRetryListeners((record,error,attempt)->diagnostics.count("transientRetry"));
    }
    private static BackOff backoff(){var backoff=new ExponentialBackOff(1000,2);backoff.setMaxInterval(30000);backoff.setMaxElapsedTime(Long.MAX_VALUE);return backoff;}
    /** 沿框架包装cause链识别数据库域；协议/本地权威冲突不按数据库抖动吞掉。 */
    public static boolean isTransient(Throwable error){
        var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        for(Throwable cause=error;cause!=null&&seen.add(cause);cause=cause.getCause()){
            if(cause instanceof ApiFailure)return false;
            if(cause instanceof DataAccessException||cause instanceof CannotCreateTransactionException||cause instanceof TransactionTimedOutException||cause instanceof SQLException)return true;
        }
        return false;
    }
    @Override public boolean seeksAfterHandling(){return true;}
    @Override public boolean isAckAfterHandle(){return false;}
    @Override public void clearThreadState(){transientErrors.clearThreadState();}
    @Override public void handleRemaining(Exception error,List<ConsumerRecord<?,?>> records,Consumer<?,?> consumer,MessageListenerContainer container){
        // 1. 标准DefaultErrorHandler管理同offset重试，1/2/4/.../30秒封顶，不忙循环、不自动恢复为成功。
        if(isTransient(error)){transientErrors.handleRemaining(error,records,consumer,container);return;}
        // 2. 永久毒消息先seek同poll所有未处理记录，不能因一条坏消息丢掉其他分区的待处理记录。
        var offsets=new HashMap<TopicPartition,Long>();for(var record:records)offsets.merge(new TopicPartition(record.topic(),record.partition()),record.offset(),Math::min);
        offsets.forEach(consumer::seek);
        // 3. 仅暂停问题分区；其他分区继续，不确认源offset、不记录原始payload或伪造DLT成功。
        if(!records.isEmpty()){var first=records.get(0);container.pausePartition(new TopicPartition(first.topic(),first.partition()));diagnostics.count("pausedPartition");diagnostics.count("poisonPause");}
        diagnostics.count("conflict");
    }
}
