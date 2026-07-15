package com.itwray.iw.auth.dao;

import com.itwray.iw.auth.mapper.BaseManagedSecretMapper;
import com.itwray.iw.auth.model.entity.BaseManagedSecretEntity;
import com.itwray.iw.web.dao.BaseDao;
import org.springframework.stereotype.Component;

@Component
public class BaseManagedSecretDao extends BaseDao<BaseManagedSecretMapper, BaseManagedSecretEntity> {

}
