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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;

import static java.util.Collections.singletonList;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.AttributeInUseException;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.ModificationItem;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.LdapName;
import net.tirasa.connid.bundles.ldap.LdapConnection;

import static net.tirasa.connid.bundles.ldap.commons.LdapUtil.escapeAttrValue;

import net.tirasa.connid.bundles.ldap.search.LdapSearches;
import net.tirasa.connid.bundles.ldap.search.LdapSearchResultsHandler;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.exceptions.ConnectorException;

public class GroupHelper {

    // This must be sorted in ascending order for Arrays.binarySearch to work properly!
    private static final String[] OBJECT_CLASSES_WITH_MANDATORY_MEMB_ATTR = {"groupOfNames", "groupOfUniqueNames"};

    private static final Log log = Log.getLog(GroupHelper.class);

    private final LdapConnection conn;

    public GroupHelper(LdapConnection conn) {
        this.conn = conn;
    }

    /**
     * Returns the attribute which POSIX group references its members. The members of a POSIX groups are held in the
     * <code>memberUid</code> attributes, and the values of this attributes are the
     * <code>uid</code> attributes of the group members. So this method returns
     * <code>"uid"</code>.
     */
    public static String getPosixRefAttribute() {
        return "uid";
    }

    public String getAliasRefAttribute() {
    	String aliasRef = conn.getConfiguration().getAliasGroupMemberRefAttribute();
        if (aliasRef == null) {
        	aliasRef = "mail";
        }
        return aliasRef;
    }
    
    private String getLdapGroupMemberAttribute() {
        String memberAttr = conn.getConfiguration().getGroupMemberAttribute();
        if (memberAttr == null) {
            memberAttr = "uniqueMember"; // For groupOfUniqueNames.
        }
        return memberAttr;
    }
    
    public String getAliasGroupMemberAttribute(){
    	String memberAttr = conn.getConfiguration().getAliasGroupMemberAttribute();
    	if (memberAttr == null || memberAttr.equals("")){
    		memberAttr = "rfc822MailMember"; // For nisMailAlias
    	}
    	return memberAttr;
    }

    public List<String> getLdapGroups(String entryDN) {
        log.ok("Retrieving LDAP groups for {0}", entryDN);
        String filter = createAttributeFilter(getLdapGroupMemberAttribute(), singletonList(entryDN));
        ToDNHandler handler = new ToDNHandler();
        LdapSearches.findEntries(handler, conn, filter);
        return handler.getResults();
    }

    public Set<GroupMembership> getLdapGroupMemberships(String entryDN) {
        log.ok("Retrieving LDAP group memberships for {0}", entryDN);
        String filter = createAttributeFilter(getLdapGroupMemberAttribute(), singletonList(entryDN));
        ToGroupMembershipHandler handler = new ToGroupMembershipHandler();
        handler.setMemberRef(entryDN);
        LdapSearches.findEntries(handler, conn, filter);
        return handler.getResults();
    }
    
    public void addLdapGroupMemberships(String entryDN, Collection<String> groupDNs) {
        log.ok("Adding {0} to LDAP groups {1}", entryDN, groupDNs);
        String ldapGroupMemberAttribute = getLdapGroupMemberAttribute();
        for (String groupDN : groupDNs) {
            addMemberToGroup(ldapGroupMemberAttribute, entryDN, groupDN);
        }
    }

    public void removeLdapGroupMemberships(String entryDN, Collection<String> groupDNs) {
        log.ok("Removing {0} from LDAP groups {1}", entryDN, groupDNs);
        String ldapGroupMemberAttribute = getLdapGroupMemberAttribute();
        for (String groupDN : groupDNs) {
            removeMemberFromGroup(ldapGroupMemberAttribute, entryDN, groupDN);
        }
    }

    public void modifyLdapGroupMemberships(Modification<GroupMembership> mod) {
        log.ok("Modifying LDAP group memberships: removing {0}, adding {1}", mod.getRemoved(), mod.getAdded());
        String ldapGroupMemberAttribute = getLdapGroupMemberAttribute();
        for (GroupMembership membership : mod.getRemoved()) {
            removeMemberFromGroup(ldapGroupMemberAttribute, membership.getMemberRef(), membership.getGroupDN());
        }
        for (GroupMembership membership : mod.getAdded()) {
            addMemberToGroup(ldapGroupMemberAttribute, membership.getMemberRef(), membership.getGroupDN());
        }
    }

    public List<String> getPosixGroups(Collection<String> posixRefAttrs) {
        log.ok("Retrieving POSIX groups for {0}", posixRefAttrs);
        String filter = createAttributeFilter("memberUid", posixRefAttrs);
        ToDNHandler handler = new ToDNHandler();
        LdapSearches.findEntries(handler, conn, filter);
        return handler.getResults();
    }

    public Set<GroupMembership> getPosixGroupMemberships(Collection<String> posixRefAttrs) {
        log.ok("Retrieving POSIX group memberships for {0}", posixRefAttrs);
        ToGroupMembershipHandler handler = new ToGroupMembershipHandler();
        for (String posixRefAttr : posixRefAttrs) {
            String filter = createAttributeFilter("memberUid", singletonList(posixRefAttr));
            handler.setMemberRef(posixRefAttr);
            LdapSearches.findEntries(handler, conn, filter);
        }
        return handler.getResults();
    }
    
    public Set<GroupMembership> getAliasGroupMemberships(Set<String> aliasRefAttrs) {
    	log.ok("Retrieving Alias group memberships for {0}", aliasRefAttrs);
    	ToGroupMembershipHandler handler = new ToGroupMembershipHandler();
        if (aliasRefAttrs != null) {
            for (String aliasRefAttr : aliasRefAttrs) {
                String filter = createAttributeFilter(getAliasGroupMemberAttribute(), singletonList(aliasRefAttr));
                handler.setMemberRef(aliasRefAttr);
                LdapSearches.findEntries(handler, conn, filter);
            }
        }
        return handler.getResults();
	}

    public void addPosixGroupMemberships(String posixRefAttr, Collection<String> groupDNs) {
        log.ok("Adding {0} to POSIX groups {1}", posixRefAttr, groupDNs);
        for (String groupDN : groupDNs) {
            addMemberToGroup("memberUid", posixRefAttr, groupDN);
        }
    }
    
    public void removePosixGroupMemberships(Set<GroupMembership> memberships) {
        log.ok("Removing POSIX group memberships {0}", memberships);
        for (GroupMembership membership : memberships) {
            removeMemberFromGroup("memberUid", membership.getMemberRef(), membership.getGroupDN());
        }
    }

    public void modifyPosixGroupMemberships(Modification<GroupMembership> mod) {
        log.ok("Modifying POSIX group memberships: removing {0}, adding {1}",
                mod.getRemoved(), mod.getAdded());
        for (GroupMembership membership : mod.getRemoved()) {
            removeMemberFromGroup("memberUid", membership.getMemberRef(), membership.getGroupDN());
        }
        for (GroupMembership membership : mod.getAdded()) {
            addMemberToGroup("memberUid", membership.getMemberRef(), membership.getGroupDN());
        }
    }
    
    public void addAliasGroupMemberships(String aliasRefAttr, Collection<String> groupDNs) {
        log.ok("Adding {0} to Alias groups {1}", aliasRefAttr, groupDNs);
        for (String groupDN : groupDNs) {
            addMemberToGroup(getAliasGroupMemberAttribute(), aliasRefAttr, groupDN);
        }
    }

    public void removeAliasGroupMembership(Set<GroupMembership> memberships) {
        log.ok("Removing Alias group memberships {0}", memberships);
        for (GroupMembership membership : memberships) {
            removeMemberFromGroup(getAliasGroupMemberAttribute(), membership.getMemberRef(), membership.getGroupDN());
        }
    }
    
    public void modifyAliasGroupMemberships(Modification<GroupMembership> mod) {
		log.ok("Modifying ALIAS group memberships: removing {0}, adding {1}", mod.getRemoved(), mod.getAdded());
		for(GroupMembership membership: mod.getRemoved()){
			removeMemberFromGroup(getAliasGroupMemberAttribute(), membership.getMemberRef(), membership.getGroupDN());
		}
		for(GroupMembership membership: mod.getAdded()){
			addMemberToGroup(getAliasGroupMemberAttribute(), membership.getMemberRef(), membership.getGroupDN());
		}
	}

    public void addMemberAttributeIfMissing(final BasicAttributes ldapAttrs) {
        String[] groupObjectClasses = conn.getConfiguration().getGroupObjectClasses();
        boolean found = false;
        for (int i = 0; i < groupObjectClasses.length; i++) {
            found = Arrays.binarySearch(OBJECT_CLASSES_WITH_MANDATORY_MEMB_ATTR, groupObjectClasses[i]) >= 0;
            if (found) {
                break;
            }
        }
        if (!found) {
            return;
        }

        javax.naming.directory.Attribute memberAttr = null;
        final NamingEnumeration<javax.naming.directory.Attribute> attrEnum = ldapAttrs.getAll();
        while (attrEnum.hasMoreElements()) {
            javax.naming.directory.Attribute attr = attrEnum.nextElement();
            if (conn.getConfiguration().getGroupMemberAttribute().equals(attr.getID())) {
                memberAttr = attr;
            }
        }

        if (memberAttr == null) {
            memberAttr = new BasicAttribute(conn.getConfiguration().getGroupMemberAttribute());
            ldapAttrs.put(memberAttr);
        }

        if (memberAttr.size() == 0) {
            memberAttr.add(conn.getConfiguration().getPrincipal());
        }
    }

    private String createAttributeFilter(String memberAttr, Collection<?> memberValues) {
        StringBuilder builder = new StringBuilder();
        boolean multi = memberValues.size() > 1;
        if (multi) {
            builder.append("(|");
        }
        for (Object memberValue : memberValues) {
            builder.append('(');
            builder.append(memberAttr);
            builder.append('=');
            escapeAttrValue(memberValue, builder);
            builder.append(')');
        }
        if (multi) {
            builder.append(")");
        }
        return builder.toString();
    }

    private void addMemberToGroup(String memberAttr, String memberValue, String groupDN) {
        BasicAttribute attr = new BasicAttribute(memberAttr, memberValue);
        ModificationItem item = new ModificationItem(DirContext.ADD_ATTRIBUTE,
                attr);
        try {
            conn.getInitialContext().modifyAttributes(groupDN, new ModificationItem[]{item});
        } catch (AttributeInUseException e) {
            throw new ConnectorException(conn.format("memberAlreadyInGroup", null, memberValue, groupDN), e);
        } catch (NamingException e) {
            throw new ConnectorException(e);
        }
    }

    private void removeMemberFromGroup(String memberAttr, String memberValue, String groupDN) {
        BasicAttribute attr = new BasicAttribute(memberAttr, memberValue);
        ModificationItem item = new ModificationItem(DirContext.REMOVE_ATTRIBUTE, attr);
        try {
            conn.getInitialContext().modifyAttributes(groupDN, new ModificationItem[]{item});
        } catch (NamingException e) {
            throw new ConnectorException(e);
        }
    }

    public static final class GroupMembership {

        private final String memberRef;

        private final String groupDN;

        public GroupMembership(String memberRef, String groupDn) {
            this.memberRef = memberRef;
            this.groupDN = groupDn;
        }

        public String getMemberRef() {
            return memberRef;
        }

        public String getGroupDN() {
            return groupDN;
        }

        @Override
        public int hashCode() {
            return memberRef.hashCode() ^ groupDN.hashCode();
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof GroupMembership) {
                GroupMembership that = (GroupMembership) o;
                if (!memberRef.equals(that.memberRef)) {
                    return false;
                }
                if (!groupDN.equals(that.groupDN)) {
                    return false;
                }
                return true;
            }
            return false;
        }

        @Override
        public String toString() {
            return "GroupMembership[memberRef: " + memberRef + "; groupDN: " + groupDN + "]";
        }
    }

    public static final class Modification<T> {

        private final Set<T> removed = new LinkedHashSet<T>();

        private final Set<T> added = new LinkedHashSet<T>();

        private Set<T> effectiveAdded;

        private Set<T> effectiveRemoved;

        public void add(T item) {
            added.add(item);
            invalidate();
        }

        public void addAll(Collection<? extends T> items) {
            for (T item : items) {
                added.add(item);
            }
            invalidate();
        }

        public void clearAdded() {
            added.clear();
            invalidate();
        }

        public Set<T> getAdded() {
            if (effectiveAdded == null) {
                effectiveAdded = new LinkedHashSet<T>(added);
                effectiveAdded.removeAll(removed);
            }
            return effectiveAdded;
        }

        public void remove(T item) {
            removed.add(item);
            invalidate();
        }

        public void removeAll(Collection<? extends T> items) {
            for (T item : items) {
                removed.add(item);
            }
            invalidate();
        }

        public Set<T> getRemoved() {
            if (effectiveRemoved == null) {
                effectiveRemoved = new LinkedHashSet<T>(removed);
                effectiveRemoved.removeAll(added);
            }
            return effectiveRemoved;
        }

        private void invalidate() {
            effectiveAdded = null;
            effectiveRemoved = null;
        }
    }

    private static final class ToDNHandler implements LdapSearchResultsHandler {

        private final List<String> results = new ArrayList<String>();

        @Override
        public boolean handle(String baseDN, SearchResult searchResult)
                throws NamingException {

            results.add(LdapEntry.create(baseDN, searchResult).getDN().toString());
            return true;
        }

        public List<String> getResults() {
            return results;
        }
    }

    private static final class ToGroupMembershipHandler implements LdapSearchResultsHandler {

        private final Set<GroupMembership> results = new HashSet<GroupMembership>();

        private String memberRef;

        public void setMemberRef(String memberRef) {
            this.memberRef = memberRef;
        }

        @Override
        public boolean handle(String baseDN, SearchResult searchResult)
                throws NamingException {

            LdapName groupDN = LdapEntry.create(baseDN, searchResult).getDN();
            results.add(new GroupMembership(memberRef, groupDN.toString()));
            return true;
        }

        public Set<GroupMembership> getResults() {
            return results;
        }
    }
}
