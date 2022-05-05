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

import static net.tirasa.connid.bundles.ldap.commons.LdapUtil.quietCreateLdapName;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.ModificationItem;

import org.identityconnectors.common.CollectionUtil;
import org.identityconnectors.common.Pair;
import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.common.exceptions.ConnectorException;
import org.identityconnectors.framework.common.objects.Attribute;
import org.identityconnectors.framework.common.objects.AttributeUtil;
import org.identityconnectors.framework.common.objects.Name;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.OperationalAttributes;
import org.identityconnectors.framework.common.objects.Uid;

import net.tirasa.connid.bundles.ldap.LdapConnection;
import net.tirasa.connid.bundles.ldap.commons.GroupHelper;
import net.tirasa.connid.bundles.ldap.commons.GroupHelper.GroupMembership;
import net.tirasa.connid.bundles.ldap.commons.GroupHelper.Modification;
import net.tirasa.connid.bundles.ldap.commons.LdapConstants;
import net.tirasa.connid.bundles.ldap.commons.LdapModifyOperation;
import net.tirasa.connid.bundles.ldap.commons.LdapUtil;
import net.tirasa.connid.bundles.ldap.commons.StatusManagement;
import net.tirasa.connid.bundles.ldap.schema.GuardedPasswordAttribute;
import net.tirasa.connid.bundles.ldap.schema.GuardedPasswordAttribute.Accessor;
import net.tirasa.connid.bundles.ldap.search.LdapSearches;

public class LdapUpdate extends LdapModifyOperation {

    private final ObjectClass oclass;

    private final Uid uid;
    
    private final GroupHelper groupHelper;

    private final String RESET_PASSWORD = "RESET_PASSWORD";
    public LdapUpdate(
            final LdapConnection conn,
            final ObjectClass oclass,
            final Uid uid) {

        super(conn);
        this.oclass = oclass;
        this.uid = uid;
        this.groupHelper = new GroupHelper(conn);
    }

    public Uid update(final Set<Attribute> attrs) {
        String entryDN = LdapSearches.findEntryDN(conn, oclass, uid);
        PosixGroupMember posixMember = new PosixGroupMember(entryDN);
        AliasGroupMember aliasMember = new AliasGroupMember(entryDN);

        // Extract the Name attribute if any, to be used to rename the entry later.
        Set<Attribute> updateAttrs = attrs;

        Name newName = AttributeUtil.getNameFromAttributes(attrs);

        // serach for status attribute
        final Attribute status = AttributeUtil.find(OperationalAttributes.ENABLE_NAME, attrs);

        String newEntryDN = null;

        if (newName != null) {
            updateAttrs = CollectionUtil.newSet(attrs);
            updateAttrs.remove(newName);
            updateAttrs.remove(AttributeUtil.find(LdapUtil.getDNAttributeName(newName), attrs));
            newEntryDN = conn.getSchemaMapping().getEntryDN(oclass, newName);
        }

        final List<String> ldapGroups = getStringListValue(updateAttrs, LdapConstants.LDAP_GROUPS_NAME);

        final List<String> posixGroups = getStringListValue(updateAttrs, LdapConstants.POSIX_GROUPS_NAME);
        
        final List<String> aliasGroups = getStringListValue(updateAttrs, LdapConstants.ALIAS_GROUPS_NAME);

        final Pair<Attributes, GuardedPasswordAttribute> attrToModify = getAttributesToModify(updateAttrs);

        final Attributes ldapAttrs = attrToModify.first;

        // If we are removing all POSIX ref attributes, check they are not used
        // in POSIX groups. Note it is OK to update the POSIX ref attribute instead of
        // removing them -- we will update the groups to refer to the new attributes.
        final Set<String> newPosixRefAttrs = getAttributeValues(
                GroupHelper.getPosixRefAttribute(),
                LdapUtil.quietCreateLdapName(newEntryDN != null ? newEntryDN : entryDN),
                ldapAttrs);

        if (newPosixRefAttrs != null && newPosixRefAttrs.isEmpty()) {
            checkRemovedPosixRefAttrs(posixMember.getPosixRefAttributes(),
                    posixMember.getPosixGroupMemberships());
        }
        
        final Set<String> newAliasRefAttrs = getAttributeValues(groupHelper.getAliasRefAttribute(), quietCreateLdapName(newEntryDN!=null? newEntryDN: entryDN), ldapAttrs);
        if (newAliasRefAttrs != null && newAliasRefAttrs.isEmpty()){
        	checkRemovedAliasRefAttrs(aliasMember.getAliasRefAttributes(), aliasMember.getAliasGroupMemberships());
        }

        if (status != null && status.getValue() != null && !status.getValue().isEmpty()) {
            StatusManagement.getInstance(
                    conn.getConfiguration().getStatusManagementClass()).
                    setStatus((Boolean) status.getValue().get(0),
                            ldapAttrs,
                            posixGroups,
                            ldapGroups);
        }

        // Update the attributes.
        modifyAttributes(entryDN, attrToModify, DirContext.REPLACE_ATTRIBUTE);

        // Rename the entry if needed.
        String oldEntryDN = null;

        if (newName != null) {
            if (newPosixRefAttrs != null && conn.getConfiguration().
                    isMaintainPosixGroupMembership() || posixGroups != null) {
                posixMember.getPosixRefAttributes();
            }
            if (newAliasRefAttrs != null && conn.getConfiguration().isMaintainAliasGroupMembership() || aliasGroups != null){
            	aliasMember.getAliasRefAttributes();
            }
            oldEntryDN = entryDN;
            entryDN = conn.getSchemaMapping().rename(oclass, oldEntryDN, newName);
        }

        // Update the LDAP groups.
        final Modification<GroupMembership> ldapGroupMod = new Modification<GroupMembership>();

        if (oldEntryDN != null && conn.getConfiguration().isMaintainLdapGroupMembership()) {
            Set<GroupMembership> members = groupHelper.getLdapGroupMemberships(oldEntryDN);
            ldapGroupMod.removeAll(members);
            for (GroupMembership member : members) {
                ldapGroupMod.add(new GroupMembership(entryDN, member.getGroupDN()));
            }
        }

        if (ldapGroups != null) {
            Set<GroupMembership> members = groupHelper.getLdapGroupMemberships(entryDN);
            ldapGroupMod.removeAll(members);
            ldapGroupMod.clearAdded(); // Since we will be replacing with the new groups.
            for (String ldapGroup : ldapGroups) {
                ldapGroupMod.add(new GroupMembership(entryDN, ldapGroup));
            }
        }

        groupHelper.modifyLdapGroupMemberships(ldapGroupMod);

        // Update the POSIX groups.
        final Modification<GroupMembership> posixGroupMod = new Modification<GroupMembership>();

        if (newPosixRefAttrs != null && conn.getConfiguration().isMaintainPosixGroupMembership()) {
            Set<String> removedPosixRefAttrs = new HashSet<String>(posixMember.getPosixRefAttributes());
            removedPosixRefAttrs.removeAll(newPosixRefAttrs);
            Set<GroupMembership> members = posixMember.getPosixGroupMembershipsByAttrs(removedPosixRefAttrs);
            posixGroupMod.removeAll(members);
            if (!members.isEmpty()) {
                String firstPosixRefAttr = getFirstPosixRefAttr(entryDN, newPosixRefAttrs);
                for (GroupMembership member : members) {
                    posixGroupMod.add(new GroupMembership(firstPosixRefAttr, member.getGroupDN()));
                }
            }
        }

        if (posixGroups != null) {
            Set<GroupMembership> members = posixMember.getPosixGroupMemberships();
            posixGroupMod.removeAll(members);
            posixGroupMod.clearAdded(); // Since we will be replacing with the new groups.
            if (!posixGroups.isEmpty()) {
                String firstPosixRefAttr = getFirstPosixRefAttr(entryDN, newPosixRefAttrs);
                for (String posixGroup : posixGroups) {
                    posixGroupMod.add(new GroupMembership(firstPosixRefAttr, posixGroup));
                }
            }
        }

        groupHelper.modifyPosixGroupMemberships(posixGroupMod);
        
        //update alias groups
        Modification<GroupMembership> aliasGroupMod = new Modification<GroupMembership>();
        if (newAliasRefAttrs != null && conn.getConfiguration().isMaintainAliasGroupMembership()) {
            Set<String> removedAliasRefAttrs = new HashSet<String>(aliasMember.getAliasRefAttributes());
            removedAliasRefAttrs.removeAll(newAliasRefAttrs);
            Set<GroupMembership> members = aliasMember.getAliasGroupMembershipsByAttrs(removedAliasRefAttrs);
            aliasGroupMod.removeAll(members);
            if (!members.isEmpty()) {
                String firstAliasRefAttr = getFirstAliasRefAttr(entryDN, newAliasRefAttrs);
                for (GroupMembership member : members) {
                    aliasGroupMod.add(new GroupMembership(firstAliasRefAttr, member.getGroupDN()));
                }
            }
        }
        
        if (aliasGroups != null){
        	Set<GroupMembership> members = aliasMember.getAliasGroupMemberships();
        	aliasGroupMod.removeAll(members);
        	aliasGroupMod.clearAdded(); // Since we will be replacing with the new groups.
        	if (!aliasGroups.isEmpty()){
        		String firstAliasRefAttr = getFirstAliasRefAttr(entryDN, newAliasRefAttrs);
        		for (String aliasGroup : aliasGroups){
        			aliasGroupMod.add(new GroupMembership(firstAliasRefAttr, aliasGroup));
        		}
        				
        	}        	
        }
        
        groupHelper.modifyAliasGroupMemberships(aliasGroupMod);

        return conn.getSchemaMapping().createUid(oclass, entryDN);
    }

    public Uid addAttributeValues(final Set<Attribute> attrs) {
        String entryDN = LdapSearches.findEntryDN(conn, oclass, uid);
        PosixGroupMember posixMember = new PosixGroupMember(entryDN);

        Pair<Attributes, GuardedPasswordAttribute> attrsToModify = getAttributesToModify(attrs);
        modifyAttributes(entryDN, attrsToModify, DirContext.ADD_ATTRIBUTE);

        List<String> ldapGroups = getStringListValue(attrs, LdapConstants.LDAP_GROUPS_NAME);
        if (!CollectionUtil.isEmpty(ldapGroups)) {
            groupHelper.addLdapGroupMemberships(entryDN, ldapGroups);
        }

        List<String> posixGroups = getStringListValue(attrs, LdapConstants.POSIX_GROUPS_NAME);
        if (!CollectionUtil.isEmpty(posixGroups)) {
            Set<String> posixRefAttrs = posixMember.getPosixRefAttributes();
            String posixRefAttr = getFirstPosixRefAttr(entryDN, posixRefAttrs);
            groupHelper.addPosixGroupMemberships(posixRefAttr, posixGroups);
        }

        return uid;
    }

    public Uid removeAttributeValues(final Set<Attribute> attrs) {
        String entryDN = LdapSearches.findEntryDN(conn, oclass, uid);
        PosixGroupMember posixMember = new PosixGroupMember(entryDN);

        Pair<Attributes, GuardedPasswordAttribute> attrsToModify = getAttributesToModify(attrs);
        Attributes ldapAttrs = attrsToModify.first;

        Set<String> removedPosixRefAttrs = getAttributeValues(GroupHelper.getPosixRefAttribute(), null, ldapAttrs);
        if (!CollectionUtil.isEmpty(removedPosixRefAttrs)) {
            checkRemovedPosixRefAttrs(removedPosixRefAttrs, posixMember.getPosixGroupMemberships());
        }

        modifyAttributes(entryDN, attrsToModify, DirContext.REMOVE_ATTRIBUTE);

        List<String> ldapGroups = getStringListValue(attrs, LdapConstants.LDAP_GROUPS_NAME);
        if (!CollectionUtil.isEmpty(ldapGroups)) {
            groupHelper.removeLdapGroupMemberships(entryDN, ldapGroups);
        }

        List<String> posixGroups = getStringListValue(attrs, LdapConstants.POSIX_GROUPS_NAME);
        if (!CollectionUtil.isEmpty(posixGroups)) {
            Set<GroupMembership> members = posixMember.getPosixGroupMembershipsByGroups(posixGroups);
            groupHelper.removePosixGroupMemberships(members);
        }

        return uid;
    }
    
    private void checkRemovedPosixRefAttrs(
            final Set<String> removedPosixRefAttrs,
            final Set<GroupMembership> memberships) {

        for (GroupMembership membership : memberships) {
            if (removedPosixRefAttrs.contains(membership.getMemberRef())) {
                throw new ConnectorException(
                        conn.format("cannotRemoveBecausePosixMember", GroupHelper.getPosixRefAttribute()));
            }
        }
    }
    
    private void checkRemovedAliasRefAttrs(Set<String> removedAliasRefAttrs, Set<GroupMembership> memberships) {
        for (GroupMembership membership : memberships) {
            if (removedAliasRefAttrs.contains(membership.getMemberRef())) {
                throw new ConnectorException(conn.format("cannotRemoveBecauseAliasMember", groupHelper.getAliasRefAttribute()));
            }
        }
    }

    private Pair<Attributes, GuardedPasswordAttribute> getAttributesToModify(final Set<Attribute> attrs) {
        BasicAttributes ldapAttrs = new BasicAttributes();
        GuardedPasswordAttribute pwdAttr = null;
        boolean resetPassword = false;
        for (Attribute attr : attrs) {
            javax.naming.directory.Attribute ldapAttr = null;
            if (attr.is(Uid.NAME)) {
                throw new IllegalArgumentException("Unable to modify an object's uid");
            } else if (attr.is(Name.NAME)) {
                // Such a change would have been handled in update() above.
                throw new IllegalArgumentException("Unable to modify an object's name");
            } else if (LdapConstants.isLdapGroups(attr.getName())) {
                // Handled elsewhere.
            } else if (LdapConstants.isPosixGroups(attr.getName())) {
                // Handled elsewhere.
            } else if (LdapConstants.isAliasGroups(attr.getName())) {
            	// Handled elsewhere
            } else if (attr.is(OperationalAttributes.PASSWORD_NAME)) {
                pwdAttr = conn.getSchemaMapping().encodePassword(oclass, attr);
            } else if (attr.is(RESET_PASSWORD)) {
            	resetPassword = true;
            } else {
                ldapAttr = conn.getSchemaMapping().encodeAttribute(oclass, attr);
            }
            if (ldapAttr != null) {
                javax.naming.directory.Attribute existingAttr = ldapAttrs.get(ldapAttr.getID());
                if (existingAttr != null) {
                    try {
                        NamingEnumeration<?> all = ldapAttr.getAll();
                        while (all.hasMoreElements()) {
                            existingAttr.add(all.nextElement());
                        }
                    } catch (NamingException e) {
                        throw new ConnectorException(e);
                    }
                } else {
                    ldapAttrs.put(ldapAttr);
                }
            }
        }
        
        if (resetPassword) {
        	pwdAttr = GuardedPasswordAttribute.create(conn.getConfiguration().getPasswordAttribute(), generateRandomPassword(30));
        }
        
        return new Pair<Attributes, GuardedPasswordAttribute>(ldapAttrs, pwdAttr);
    }
    
    /**
     * Generate random password, efectively blocking account (account is not disabled, but nobody knows password)
     * @param length
     * @return
     */
    private GuardedString generateRandomPassword(int length) {
    	byte[] array = new byte[length];
        new Random().nextBytes(array);
        String generatedString = new String(array, Charset.forName("UTF-8"));
        
    	return new GuardedString(generatedString.toCharArray());
    }

    private void modifyAttributes(final String entryDN, Pair<Attributes, GuardedPasswordAttribute> attrs,
            final int ldapModifyOp) {

        final List<ModificationItem> modItems = new ArrayList<ModificationItem>(attrs.first.size());
        NamingEnumeration<? extends javax.naming.directory.Attribute> attrEnum = attrs.first.getAll();
        while (attrEnum.hasMoreElements()) {
            modItems.add(new ModificationItem(ldapModifyOp, attrEnum.nextElement()));
        }

        if (attrs.second != null) {
            attrs.second.access(new Accessor() {

                @Override
                public void access(javax.naming.directory.Attribute passwordAttr) {
                    // Do not add the password to the result Attributes because it is a guarded value.
                    hashPassword(passwordAttr, entryDN);
                    modItems.add(new ModificationItem(ldapModifyOp, passwordAttr));
                    modifyAttributes(entryDN, modItems);
                }
            });
        } else {
            modifyAttributes(entryDN, modItems);
        }
    }

    private void modifyAttributes(final String entryDN, final List<ModificationItem> modItems) {
        try {
            conn.getInitialContext().modifyAttributes(entryDN, modItems.toArray(new ModificationItem[modItems.size()]));
        } catch (NamingException e) {
            throw new ConnectorException(e);
        }
    }

    private List<String> getStringListValue(final Set<Attribute> attrs, final String attrName) {
        Attribute attr = AttributeUtil.find(attrName, attrs);
        return attr == null
                ? null
                : LdapUtil.checkedListByFilter(CollectionUtil.nullAsEmpty(attr.getValue()), String.class);
    }
}
