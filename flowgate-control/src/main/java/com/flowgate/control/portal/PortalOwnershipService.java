package com.flowgate.control.portal;

import java.util.*;
import org.springframework.stereotype.Service;

/** 归属查询使用同一404，隐藏跨工作区对象是否存在。 */
@Service
public class PortalOwnershipService {
    private final PortalStore db;
    public PortalOwnershipService(PortalStore db){this.db=db;}
    public Map<String,Object> developer(long user,long developer){return db.need("SELECT d.* FROM fg_developer d JOIN fg_developer_member m ON m.developer_id=d.id WHERE m.principal_id=? AND d.id=? AND m.status='ACTIVE' AND m.role='OWNER'",user,developer);}
    public Map<String,Object> app(long user,long app){return db.need("SELECT a.* FROM fg_application a JOIN fg_developer_member m ON m.developer_id=a.developer_id WHERE m.principal_id=? AND a.id=? AND m.status='ACTIVE' AND m.role='OWNER'",user,app);}
    public void lockAppOwner(long user,long app){db.need("SELECT m.principal_id FROM fg_application a JOIN fg_developer_member m ON m.developer_id=a.developer_id JOIN fg_portal_user u ON u.principal_id=m.principal_id JOIN fg_principal p ON p.id=u.principal_id WHERE a.id=? AND m.principal_id=? AND m.status='ACTIVE' AND m.role='OWNER' AND u.status='ACTIVE' AND p.enabled=TRUE FOR SHARE",app,user);}
    public Object developers(long user){return db.rows("SELECT d.id,d.name,d.status FROM fg_developer d JOIN fg_developer_member m ON m.developer_id=d.id WHERE m.principal_id=? AND m.status='ACTIVE' AND m.role='OWNER' ORDER BY d.id",user);}
}
