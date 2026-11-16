package com.demo.rabbitmq.entity;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDateTime;

/**
 * 死信消息表实体（user_dead_letter）。
 *
 * 用途：积分消费重试耗尽后，把消息原文、异常、来源队列落库，
 * 便于事后排查、人工重放，避免消息进入死信队列后无人知晓。
 *
 * 字段与建表语句对应关系：
 *   msg_id        MQ 消息 ID（生产者发送时写入，用于关联原始消息）
 *   topic         来源队列 / 交换机，便于按来源分类处理
 *   exception_msg 最后一次消费失败的异常信息（截断到 500 字符）
 *   raw_data      消息体原文（JSON）
 *   create_date   落库时间，由 {@link #prePersist()} 自动填充
 */
@Entity
@Table(name = "user_dead_letter")
public class UserDeadLetter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "msg_id", length = 64)
    private String msgId;

    @Column(name = "topic", length = 128)
    private String topic;

    @Column(name = "exception_msg", length = 500)
    private String exceptionMsg;

    @Lob
    @Column(name = "raw_data")
    private String rawData;

    @Column(name = "create_date", nullable = false, updatable = false)
    private LocalDateTime createDate;

    /** 插入前补时间：Hibernate 插入时会显式带上该字段，数据库默认值不会生效，必须自己赋值 */
    @PrePersist
    void prePersist() {
        if (createDate == null) {
            createDate = LocalDateTime.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getMsgId() { return msgId; }
    public void setMsgId(String msgId) { this.msgId = msgId; }

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }

    public String getExceptionMsg() { return exceptionMsg; }
    public void setExceptionMsg(String exceptionMsg) { this.exceptionMsg = exceptionMsg; }

    public String getRawData() { return rawData; }
    public void setRawData(String rawData) { this.rawData = rawData; }

    public LocalDateTime getCreateDate() { return createDate; }
    public void setCreateDate(LocalDateTime createDate) { this.createDate = createDate; }
}
