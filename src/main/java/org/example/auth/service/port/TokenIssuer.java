package org.example.auth.service.port;

import org.example.shared.api.AuthenticatedUser;

/** Login Service sẽ gọi sau kiểm tra credentials/status; CLI local chỉ phục vụ mock. */
public interface TokenIssuer {
    AccessToken issue(AuthenticatedUser user);
}
