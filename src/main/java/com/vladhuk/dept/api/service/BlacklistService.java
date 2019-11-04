package com.vladhuk.dept.api.service;

import com.vladhuk.dept.api.model.User;

import java.util.List;

public interface BlacklistService {

    List<User> getFullBlacklist();

    List<User> getBlacklistPage(Integer pageNumber, Integer pageSize);

    List<User> addUserToBlacklist(User user);

    void deleteUserFromBlacklist(Long userId);

}
