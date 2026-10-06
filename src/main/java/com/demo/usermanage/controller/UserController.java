package com.demo.usermanage.controller;

import com.demo.usermanage.common.PageResult;
import com.demo.usermanage.common.Result;
import com.demo.usermanage.dto.UserCreateRequest;
import com.demo.usermanage.dto.UserUpdateRequest;
import com.demo.usermanage.entity.User;
import com.demo.usermanage.service.UserService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;

/**
 * 用户管理 RESTful 接口。
 *
 * POST   /users        创建用户（用户名非空、邮箱格式校验）
 * GET    /users/{id}   查询单个用户
 * GET    /users        分页查询 ?page=1&size=10
 * PUT    /users/{id}   更新用户（局部更新）
 * DELETE /users/{id}   逻辑删除
 */
@Validated
@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /** 创建用户 */
    @PostMapping
    public Result<User> create(@Valid @RequestBody UserCreateRequest request) {
        return Result.success(userService.create(request));
    }

    /** 按 id 查询用户 */
    @GetMapping("/{id}")
    public Result<User> getById(@PathVariable("id") @Min(value = 1, message = "id 必须为正整数") Long id) {
        return Result.success(userService.getById(id));
    }

    /** 分页查询用户：page 从 1 开始，size 范围 1~100 */
    @GetMapping
    public Result<PageResult<User>> page(
            @RequestParam(value = "page", defaultValue = "1")
            @Min(value = 1, message = "页码 page 最小为 1") int page,
            @RequestParam(value = "size", defaultValue = "10")
            @Min(value = 1, message = "每页条数 size 最小为 1")
            @Max(value = 100, message = "每页条数 size 最大为 100") int size) {
        return Result.success(userService.page(page, size));
    }

    /** 更新用户：仅更新请求体中传入的字段 */
    @PutMapping("/{id}")
    public Result<User> update(
            @PathVariable("id") @Min(value = 1, message = "id 必须为正整数") Long id,
            @Valid @RequestBody UserUpdateRequest request) {
        return Result.success(userService.update(id, request));
    }

    /** 逻辑删除用户（status 置为 -1） */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") @Min(value = 1, message = "id 必须为正整数") Long id) {
        userService.deleteById(id);
        return Result.success();
    }
}
