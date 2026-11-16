package com.demo.rabbitmq.service;

import com.demo.rabbitmq.entity.UserDeadLetter;
import com.demo.rabbitmq.repository.UserDeadLetterRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 死信消息落库与查询服务。
 *
 * 调用场景：
 *   1. DeadLetterConsumer 消费 user.dlq 成功后调用 {@link #save(String, String, String, String)} 落库；
 *   2. 管理端 / 排查脚本调用查询、更新、删除方法。
 */
@Service
public class UserDeadLetterService {

    /** exception_msg 字段长度上限，超长会导致插入失败 */
    private static final int EXCEPTION_MSG_MAX_LENGTH = 500;

    private final UserDeadLetterRepository repository;

    public UserDeadLetterService(UserDeadLetterRepository repository) {
        this.repository = repository;
    }

    /** 新增：消费者落库入口 */
    @Transactional
    public UserDeadLetter save(String msgId, String topic, String exceptionMsg, String rawData) {
        UserDeadLetter record = new UserDeadLetter();
        record.setMsgId(msgId);
        record.setTopic(topic);
        record.setExceptionMsg(truncate(exceptionMsg));
        record.setRawData(rawData);
        return repository.save(record);
    }

    /** 新增：完整对象写入 */
    @Transactional
    public UserDeadLetter save(UserDeadLetter deadLetter) {
        return repository.save(deadLetter);
    }

    /** 主键查询 */
    @Transactional(readOnly = true)
    public Optional<UserDeadLetter> findById(Long id) {
        return repository.findById(id);
    }

    /** 全量查询：按落库时间倒序，最新的死信在最前面 */
    @Transactional(readOnly = true)
    public List<UserDeadLetter> findAll() {
        return repository.findAll(Sort.by(Sort.Direction.DESC, "createDate"));
    }

    /**
     * 分页查询（页码从 1 开始，方便管理端直接传参）。
     */
    @Transactional(readOnly = true)
    public Page<UserDeadLetter> page(int pageNo, int pageSize) {
        int pageIndex = Math.max(pageNo - 1, 0);
        int size = pageSize <= 0 ? 10 : pageSize;
        PageRequest request = PageRequest.of(pageIndex, size, Sort.by(Sort.Direction.DESC, "createDate"));
        return repository.findAll(request);
    }

    /** 按消息 ID 查询：同一条消息多次重投可能落多条 */
    @Transactional(readOnly = true)
    public List<UserDeadLetter> findByMsgId(String msgId) {
        return repository.findByMsgId(msgId);
    }

    /** 按时间范围分页查询 */
    @Transactional(readOnly = true)
    public Page<UserDeadLetter> findByCreateDateBetween(LocalDateTime start, LocalDateTime end,
                                                        int pageNo, int pageSize) {
        int pageIndex = Math.max(pageNo - 1, 0);
        int size = pageSize <= 0 ? 10 : pageSize;
        PageRequest request = PageRequest.of(pageIndex, size, Sort.by(Sort.Direction.DESC, "createDate"));
        return repository.findByCreateDateBetween(start, end, request);
    }

    /**
     * 修改：死信表没有状态字段，通常只补写异常信息和消息原文（例如人工重放后修正）。
     */
    @Transactional
    public UserDeadLetter update(Long id, String exceptionMsg, String rawData) {
        UserDeadLetter record = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("死信消息不存在, id=" + id));
        record.setExceptionMsg(truncate(exceptionMsg));
        record.setRawData(rawData);
        return repository.save(record);
    }

    /** 删除单条 */
    @Transactional
    public void deleteById(Long id) {
        repository.deleteById(id);
    }

    /** 清空：仅用于本地实验，生产环境不应暴露 */
    @Transactional
    public void deleteAll() {
        repository.deleteAll();
    }

    /** 总数：便于判断死信是否在持续增长 */
    @Transactional(readOnly = true)
    public long count() {
        return repository.count();
    }

    private String truncate(String exceptionMsg) {
        if (exceptionMsg == null || exceptionMsg.length() <= EXCEPTION_MSG_MAX_LENGTH) {
            return exceptionMsg;
        }
        return exceptionMsg.substring(0, EXCEPTION_MSG_MAX_LENGTH);
    }
}
