/* 
 * ====================
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 2008-2009 Sun Microsystems, Inc. All rights reserved.
 * 
 * The contents of this file are subject to the terms of the Common Development
 * and Distribution License("CDDL") (the "License").  You may not use this file
 * except in compliance with the License.
 * 
 * You can obtain a copy of the License at
 * http://opensource.org/licenses/cddl1.php
 * See the License for the specific language governing permissions and limitations
 * under the License.
 * 
 * When distributing the Covered Code, include this CDDL Header Notice in each file
 * and include the License file at http://opensource.org/licenses/cddl1.php.
 * If applicable, add the following below this CDDL Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 * ====================
 * Portions Copyrighted 2011 ConnId.
 */
package net.tirasa.connid.bundles.ldap.commons;

import java.util.EnumSet;

import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.common.objects.AttributeInfo;
import org.identityconnectors.framework.common.objects.AttributeInfoBuilder;
import org.identityconnectors.framework.common.objects.OperationOptions;
import org.identityconnectors.framework.common.objects.OperationalAttributes;
import org.identityconnectors.framework.common.objects.AttributeInfo.Flags;

public class LdapConstants {

    public static final String LDAP_GROUPS_NAME = "ldapGroups";

    public static final String POSIX_GROUPS_NAME = "posixGroups";

    public static final String ALIAS_GROUPS_NAME = "aliasGroups";
    
    public static final String LDAP_UID_ATTRS_NAME = "ldap_uid_attributes";

    public static final String SEARCH_FILTER_NAME = "searchFilter";

    public static final String OP_SEARCH_FILTER = "searchFilter";
    
    public static final String CONNECT_TIMEOUT_ENV_PROP = "com.sun.jndi.ldap.connect.timeout";
    
    public static final String READ_TIMEOUT_ENV_PROP = "com.sun.jndi.ldap.read.timeout";
    
    /**
     * Overrides the framework-defined password because ours is readable:
     * we can return the password from <code>sync()</code> when doing
     * password synchronization.
     */
    public static final AttributeInfo PASSWORD = AttributeInfoBuilder.build(
            OperationalAttributes.PASSWORD_NAME, GuardedString.class,
            EnumSet.of(Flags.NOT_RETURNED_BY_DEFAULT));

    public static boolean isLdapGroups(String attrName) {
        return LDAP_GROUPS_NAME.equalsIgnoreCase(attrName);
    }

    public static boolean isPosixGroups(String attrName) {
        return POSIX_GROUPS_NAME.equalsIgnoreCase(attrName);
    }
    
    public static boolean isAliasGroups(String attrName){
    	return ALIAS_GROUPS_NAME.equalsIgnoreCase(attrName);
    }

    public static String[] getLdapUidAttributes(OperationOptions options) {
        return (String[]) options.getOptions().get(LDAP_UID_ATTRS_NAME);
    }

    public static String getSearchFilter(OperationOptions options) {
        return (String) options.getOptions().get(SEARCH_FILTER_NAME);
    }

    private LdapConstants() { }
}
