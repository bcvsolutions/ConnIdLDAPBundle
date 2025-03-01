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
package net.tirasa.connid.bundles.ldap.modify;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.naming.NamingException;
import javax.naming.directory.BasicAttributes;
import net.tirasa.connid.bundles.ldap.LdapConnection;
import net.tirasa.connid.bundles.ldap.commons.GroupHelper;
import net.tirasa.connid.bundles.ldap.commons.LdapConstants;
import net.tirasa.connid.bundles.ldap.commons.LdapModifyOperation;
import net.tirasa.connid.bundles.ldap.commons.LdapUtil;
import net.tirasa.connid.bundles.ldap.commons.StatusManagement;
import net.tirasa.connid.bundles.ldap.schema.GuardedPasswordAttribute;
import net.tirasa.connid.bundles.ldap.schema.GuardedPasswordAttribute.Accessor;
import org.identityconnectors.common.CollectionUtil;
import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.common.exceptions.ConnectorException;
import org.identityconnectors.framework.common.objects.Attribute;
import org.identityconnectors.framework.common.objects.AttributeUtil;
import org.identityconnectors.framework.common.objects.Name;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.OperationOptions;
import org.identityconnectors.framework.common.objects.OperationalAttributes;
import org.identityconnectors.framework.common.objects.Uid;

public class LdapCreate extends LdapModifyOperation {

    // TODO old LDAP connector has a note about a RFC 4527 Post-Read control.
    private final ObjectClass oclass;

    private final Set<Attribute> attrs;
    
    private final GroupHelper groupHelper;

    public LdapCreate(
            final LdapConnection conn,
            final ObjectClass oclass,
            final Set<Attribute> attrs,
            final OperationOptions options) {

        super(conn);
        this.oclass = oclass;
        this.attrs = attrs;
        this.groupHelper = new GroupHelper(conn);
    }

    public Uid execute() {
        try {
            return executeImpl();
        } catch (NamingException e) {
            throw new ConnectorException(e);
        }
    }

    private Uid executeImpl()
            throws NamingException {

        final Name nameAttr = AttributeUtil.getNameFromAttributes(attrs);
        if (nameAttr == null) {
            throw new IllegalArgumentException("No Name attribute provided in the attributes");
        }

        final List<String> ldapGroups = new ArrayList<String>();
        final List<String> posixGroups = new ArrayList<String>();
        final List<String> aliasGroups = new ArrayList<String>();
        GuardedPasswordAttribute pwdAttr = null;
        Boolean status = null;
        boolean resetPassword = false;

        final BasicAttributes ldapAttrs = new BasicAttributes(true);

        for (Attribute attr : attrs) {
            javax.naming.directory.Attribute ldapAttr = null;
            if (attr.is(Name.NAME)) {
                // Handled already.
            } else if (LdapConstants.isLdapGroups(attr.getName())) {
                ldapGroups.addAll(
                        LdapUtil.checkedListByFilter(CollectionUtil.nullAsEmpty(attr.getValue()), String.class));
            } else if (LdapConstants.isPosixGroups(attr.getName())) {
                posixGroups.addAll(
                        LdapUtil.checkedListByFilter(CollectionUtil.nullAsEmpty(attr.getValue()), String.class));
            } else if (LdapConstants.isAliasGroups(attr.getName())) {
            	aliasGroups.addAll(
                        LdapUtil.checkedListByFilter(CollectionUtil.nullAsEmpty(attr.getValue()), String.class));
            } else if (attr.is(OperationalAttributes.PASSWORD_NAME)) {
                pwdAttr = conn.getSchemaMapping().encodePassword(oclass, attr);
            } else if (attr.is(OperationalAttributes.ENABLE_NAME)) {
                // manage enable/disable status
                if (attr.getValue() != null && !attr.getValue().isEmpty()) {
                    status = Boolean.parseBoolean(attr.getValue().get(0).toString());
                }
            } else if (attr.is(RESET_PASSWORD)) {
            	if (attr.getValue() != null && (Boolean) attr.getValue().get(0) == true) {
            		resetPassword = true;
            	}
            } else {
                ldapAttr = conn.getSchemaMapping().encodeAttribute(oclass, attr);
                // Do not send empty attributes. 
                // The server complains for "uniqueMember", for example.
                if (ldapAttr != null && ldapAttr.size() > 0) {
                    ldapAttrs.put(ldapAttr);
                }
            }
        }

        if (status != null) {
            StatusManagement.getInstance(conn.getConfiguration().getStatusManagementClass()).
                    setStatus(status, ldapAttrs, posixGroups, ldapGroups);
        }

        if (ObjectClass.GROUP.equals(oclass)) {
            groupHelper.addMemberAttributeIfMissing(ldapAttrs);
        }
        
        if (resetPassword) {
        	pwdAttr = GuardedPasswordAttribute.create(conn.getConfiguration().getPasswordAttribute(), new GuardedString(generateRandomPassword(30)));
        	ldapAttrs.put(AIX_PASSWORD_ATTRIBUTE, AIX_PASSWORD_PREFIX + String.valueOf(generateRandomPassword(13)));
        }

        final String[] entryDN = { null };
        if (pwdAttr != null) {
            pwdAttr.access(new Accessor() {

                @Override
                public void access(javax.naming.directory.Attribute passwordAttr) {
                    hashPassword(passwordAttr, null);
                    ldapAttrs.put(passwordAttr);
                    entryDN[0] = conn.getSchemaMapping().create(oclass, nameAttr, ldapAttrs);
                }
            });
        } else {
            entryDN[0] = conn.getSchemaMapping().create(oclass, nameAttr, ldapAttrs);
        }

        if (!CollectionUtil.isEmpty(ldapGroups)) {
            groupHelper.addLdapGroupMemberships(entryDN[0], ldapGroups);
        }

        if (!CollectionUtil.isEmpty(posixGroups)) {
            Set<String> posixRefAttrs = getAttributeValues(GroupHelper.getPosixRefAttribute(), null, ldapAttrs);
            String posixRefAttr = getFirstPosixRefAttr(entryDN[0], posixRefAttrs);
            groupHelper.addPosixGroupMemberships(posixRefAttr, posixGroups);
        }
        
        if (!CollectionUtil.isEmpty(aliasGroups)) {
        	Set<String> aliasRefAttrs = getAttributeValues(groupHelper.getAliasRefAttribute(), null, ldapAttrs);
            String aliasRefAttr = getFirstAliasRefAttr(entryDN[0], aliasRefAttrs);
            groupHelper.addAliasGroupMemberships(aliasRefAttr, aliasGroups);
        }

        return conn.getSchemaMapping().createUid(oclass, entryDN[0]);
    }
}
