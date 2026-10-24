// Interface constants for checking and requesting permissions.
//
// Same shape as OperationControl: method numbers live in one place, so NervusApp's convenience methods
// don't repeat literals at call sites.
package com.nervus.sdk.permission

/**
 * `nervus.interface.permission.self`: the "do I have this permission" check.
 *
 * Built into nervud, with **no threshold**: the target package is always the caller itself (the request has no package_id;
 * the kernel takes the identity from the connection's credentials), so it isn't a way to "check any package's grants".
 */
object PermissionSelf {
    const val INTERFACE_ID = "nervus.interface.permission.self"

    const val CHECK = 1
}

/**
 * `nervus.interface.permission.ui`: the request side of the confirmation UI.
 *
 * Implemented by `nervus.permissionui`. Only the method this SDK uses is listed here:
 * `RequestPermission` has no threshold (asking for your own permissions isn't a privilege).
 *
 * The other two methods aren't here: `ConfirmInstall` needs `perm.pkg.install` and `OpenManager`
 * needs `perm.pkg.query`; they belong to system apps and shouldn't be in a convenience API anyone can use.
 */
object PermissionUi {
    const val INTERFACE_ID = "nervus.interface.permission.ui"

    const val REQUEST_PERMISSION = 3
}
