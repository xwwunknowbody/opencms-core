/*
 * File   : $Source: /alkacon/cvs/opencms/src/org/opencms/xml/sitemap/Attic/CmsSitemapStructureCache.java,v $
 * Date   : $Date: 2010/09/24 07:01:23 $
 * Version: $Revision: 1.5 $
 *
 * This library is part of OpenCms -
 * the Open Source Content Management System
 *
 * Copyright (C) 2002 - 2009 Alkacon Software (http://www.alkacon.com)
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * For further information about Alkacon Software, please see the
 * company website: http://www.alkacon.com
 *
 * For further information about OpenCms, please see the
 * project website: http://www.opencms.org
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package org.opencms.xml.sitemap;

import org.opencms.cache.CmsVfsCache;
import org.opencms.file.CmsFile;
import org.opencms.file.CmsObject;
import org.opencms.file.CmsPropertyDefinition;
import org.opencms.file.CmsResource;
import org.opencms.file.CmsResourceFilter;
import org.opencms.file.CmsVfsResourceNotFoundException;
import org.opencms.file.types.CmsResourceTypeXmlSitemap;
import org.opencms.main.CmsException;
import org.opencms.main.CmsLog;
import org.opencms.main.OpenCms;
import org.opencms.monitor.CmsMemoryMonitor;
import org.opencms.util.CmsPair;
import org.opencms.util.CmsStringUtil;
import org.opencms.util.CmsUUID;
import org.opencms.xml.content.CmsXmlContentPropertyHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;

/**
 * A cache which saves the path data for the entire sitemap and either the Online project or Offline project(s).<p>
 * 
 * @author Michael Moossen
 * @author Georg Westenberger
 * 
 * @version $Revision: 1.5 $
 * 
 * @since 8.0.0
 */
public class CmsSitemapStructureCache extends CmsVfsCache implements I_CmsSitemapCache {

    /** The log to use (static for performance reasons).<p> */
    private static final Log LOG = CmsLog.getLog(CmsSitemapStructureCache.class);

    /** The admin context. */
    protected CmsObject m_adminCms;

    /** Sitemap entries by id. */
    protected Map<CmsUUID, CmsInternalSitemapEntry> m_byId;

    /** The set of (locale-independent) paths in the sitemap. */
    protected Set<String> m_pathSet;

    /** Cache for active sitemaps, as localized entry point root path vs sitemap resource root path. */
    private Map<String, String> m_active;

    /** Sitemap entries indexed by vfs paths and locale.*/
    private Map<CmsPair<String, Locale>, List<CmsInternalSitemapEntry>> m_byRootVfsPath;

    /** A map from structure ids to sets of corresponding sitemap paths. */
    private Map<CmsPair<CmsUUID, Locale>, List<CmsInternalSitemapEntry>> m_byStructureId;

    /** Sitemap entries by path. */
    private Map<String, CmsInternalSitemapEntry> m_byUri;

    /** The default sitemap properties. */
    private Map<String, String> m_defProps;

    /** The name of this sitemap cache. */
    private String m_name;

    /** A flag which indicates whether this is the online sitemap cache (this flag only makes sense if the <code>m_useCache</code> flag is set ). */
    private boolean m_online;

    /** The list of site roots of sites which use sitemaps. */
    private Set<String> m_siteRoots;

    /** If true, the cached data will be registered in the memory monitor. */
    private boolean m_useMemoryMonitor;

    /**
     * Initializes the cache. Only intended to be called during startup.<p>
     * 
     * @param adminCms the root admin CMS context for permission independent data retrieval 
     * @param memMonitor the memory monitor instance
     * @param useMemoryMonitor if true, the cached data will be registered in the memory monitor 
     * @param online the name that should be used for the sitemap cache
     * @param name the name of the sitemap cache   
     * @see org.opencms.main.OpenCmsCore#initConfiguration
     */
    public CmsSitemapStructureCache(
        CmsObject adminCms,
        CmsMemoryMonitor memMonitor,
        boolean useMemoryMonitor,
        boolean online,
        String name) {

        m_useMemoryMonitor = useMemoryMonitor;
        m_online = online;
        m_name = name;

        m_adminCms = adminCms;
        m_adminCms.getRequestContext().setSiteRoot("");

        initCaches(memMonitor);
        if (m_useMemoryMonitor) {
            registerEventListener();
        }
    }

    /**
     * Returns the active sitemap lookup table.<p>
     * 
     * This method is synchronized since it does not make any sense 
     * to concurrently initialize the look up table.<p>
     * 
     * @param cms the current CMS context
     * 
     * @return the active sitemap table, as localized entry point root path vs sitemap resource root path
     * 
     * @throws CmsException if something goes wrong
     */
    public synchronized Map<String, String> getActiveSitemaps(CmsObject cms) throws CmsException {

        CmsObject adminCms = internalCreateCmsObject(cms);

        // check cache
        if (m_active != null) {
            return m_active;
        }
        long t = System.currentTimeMillis();

        m_active = Collections.synchronizedMap(new HashMap<String, String>());
        register("sitemapActive", m_active);

        // clean up
        m_byId.clear();
        m_byUri.clear();
        m_pathSet.clear();
        m_byStructureId.clear();
        m_byRootVfsPath.clear();
        m_siteRoots.clear();

        // iterate sitemap entry points (system wide)
        List<CmsResource> entryPoints = internalGetEntryPointResources(adminCms);
        for (CmsResource entryPoint : entryPoints) {
            String siteRoot = OpenCms.getSiteManager().getSiteRoot(entryPoint.getRootPath());
            m_siteRoots.add(siteRoot);
            String sitemapPath = internalReadSitemapProperty(adminCms, entryPoint);
            try {
                CmsFile sitemapFile = internalReadSitemapFile(adminCms, entryPoint, sitemapPath);
                CmsXmlSitemap xmlSitemap = internalUnmarshalSitemapFile(adminCms, sitemapFile);
                visitRootSitemap(adminCms, m_active, entryPoint, sitemapFile, xmlSitemap);
            } catch (CmsException e) {
                //It's an error if a root sitemap can't be read, but we still want to process the other root sitemaps
                LOG.error("Can't read root sitemap: ");
                LOG.error(e.getLocalizedMessage(), e);
            }
        }

        LOG.debug(Messages.get().getBundle().key(
            Messages.LOG_DEBUG_NAMED_CACHE_SITEMAP_2,
            getName(),
            new Long(System.currentTimeMillis() - t)));
        return m_active;
    }

    /**
     * Returns the cache key for the given parameters.<p>
     * 
     * @param structureId the sitemap's structure id
     * @param keepEncoding if to keep the encoding while unmarshalling
     * 
     * @return the cache key for the given sitemap and parameters
     */
    public String getCacheKey(CmsUUID structureId, boolean keepEncoding) {

        return structureId.toString() + "_" + keepEncoding;
    }

    /**
     * Returns the default sitemap properties.<p>
     * 
     * @param cms the current cms context
     * 
     * @return the default sitemap properties
     */
    public Map<String, String> getDefaultProperties(CmsObject cms) {

        Map<String, String> defProps = m_defProps;
        if (defProps != null) {
            return defProps;
        }
        // default properties are not resource dependent, they are system wide, defined in the XSD
        // but we need a resource to get to the content handler so get the first sitemap we can find
        List<CmsResource> sitemaps = new ArrayList<CmsResource>();
        try {
            CmsObject adminCms = OpenCms.initCmsObject(m_adminCms);
            adminCms.getRequestContext().setCurrentProject(cms.getRequestContext().currentProject());
            sitemaps = adminCms.readResources(
                "/",
                CmsResourceFilter.requireType(OpenCms.getSitemapManager().getSitemapTypeId()),
                true);
        } catch (CmsException e) {
            // should never happen
            LOG.error(e.getLocalizedMessage(), e);
        }
        if (sitemaps.isEmpty()) {
            // can happen if there is no sitemap 
            defProps = new HashMap<String, String>();
        } else {
            CmsResource resource = sitemaps.get(0);
            defProps = CmsXmlContentPropertyHelper.mergeDefaults(cms, resource, Collections.<String, String> emptyMap());
        }
        m_defProps = new HashMap<String, String>(defProps);
        return defProps;
    }

    /**
     * Gets sitemap entries by root vfs path.<p>
     * 
     * @param cms the current CMS context 
     * @param rootPath the root path
     *  
     * @return a list of sitemap entries which point to a resource with the given root path 
     * @throws CmsException if something goes wrong 
     */
    public List<CmsInternalSitemapEntry> getEntriesByRootVfsPath(CmsObject cms, String rootPath) throws CmsException {

        getActiveSitemaps(cms);
        Locale locale = cms.getRequestContext().getLocale();
        CmsPair<String, Locale> key = CmsPair.create(rootPath, locale);
        List<CmsInternalSitemapEntry> entries = m_byRootVfsPath.get(key);
        if (entries == null) {
            return Collections.<CmsInternalSitemapEntry> emptyList();
        } else {
            return Collections.unmodifiableList(entries);
        }
    }

    /**
     * @see org.opencms.xml.sitemap.I_CmsSitemapCache#getEntriesByStructureId(org.opencms.file.CmsObject, org.opencms.util.CmsUUID)
     */
    public List<CmsInternalSitemapEntry> getEntriesByStructureId(CmsObject cms, CmsUUID id) throws CmsException {

        getActiveSitemaps(cms);
        Locale locale = cms.getRequestContext().getLocale();
        List<CmsInternalSitemapEntry> entries = m_byStructureId.get(new CmsPair<CmsUUID, Locale>(id, locale));
        if (entries == null) {
            return Collections.<CmsInternalSitemapEntry> emptyList();
        } else {
            return Collections.unmodifiableList(entries);
        }
    }

    /**
     * Returns the sitemap entry for the given id and current project.<p>
     *
     * @param cms the current CMS context
     * @param id the id to look for
     * 
     * @return the sitemap entry, or <code>null</code> if not found
     * 
     * @throws CmsException if something goes wrong 
     */
    public CmsInternalSitemapEntry getEntryById(CmsObject cms, CmsUUID id) throws CmsException {

        // ensure sitemap data is cached
        getActiveSitemaps(cms);
        Map<CmsUUID, CmsInternalSitemapEntry> entries = m_byId;
        return entries.get(id);
    }

    /**
     * Returns the sitemap entry for the given URI and current project.<p>
     *
     * @param cms the current CMS context
     * @param uri the URI to look for
     * 
     * @return the sitemap entry, or <code>null</code> if not found
     * 
     * @throws CmsException if something goes wrong 
     */
    public CmsInternalSitemapEntry getEntryByUri(CmsObject cms, String uri) throws CmsException {

        // ensure sitemap data is cached
        getActiveSitemaps(cms);
        // retrieve data
        Map<String, CmsInternalSitemapEntry> entries = m_byUri;
        String path = cms.getRequestContext().getLocale().toString() + cms.getRequestContext().addSiteRoot(uri);
        return entries.get(path);
    }

    /**
     * Returns the name of this sitemap cache.<p> 
     * 
     * @return the name of the sitemap cache 
     */
    public String getName() {

        return m_name;
    }

    /**
     * Returns the roots of the sites in which sitemaps are active.<p>
     * 
     * @param cms the current CMS context
     *  
     * @return a set of site roots 
     * 
     * @throws CmsException if something goes wrong  
     */
    public Set<String> getSiteRootsWithSitemap(CmsObject cms) throws CmsException {

        getActiveSitemaps(cms);
        return Collections.unmodifiableSet(m_siteRoots);
    }

    /**
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {

        return getClass().getName() + " (" + m_name + ")";
    }

    /**
     * Adds a sitemap entry to the map which indexes sitemap entries by the root path of the container page
     * which they point to.<p>
     * 
     * @param rootPath the root path of the container page 
     * @param locale the locale 
     * @param entry the entry to add 
     */
    protected void addEntryForRootPath(String rootPath, Locale locale, CmsInternalSitemapEntry entry) {

        CmsPair<String, Locale> key = CmsPair.create(rootPath, locale);
        List<CmsInternalSitemapEntry> entries = m_byRootVfsPath.get(key);
        if (entries == null) {
            entries = new ArrayList<CmsInternalSitemapEntry>();
            m_byRootVfsPath.put(key, entries);
        }
    }

    /**
     * Adds a sitemap entry to the map which indexes sitemap entries by their structure id (and locale).<p>
     * 
     * @param structureId the structure id of the resource (first part of the key)  
     * @param locale the locale  (second part of the key) 
     * @param entry the entry to add 
     */
    protected void addEntryForStructureId(CmsUUID structureId, Locale locale, CmsInternalSitemapEntry entry) {

        CmsPair<CmsUUID, Locale> key = new CmsPair<CmsUUID, Locale>(structureId, locale);

        List<CmsInternalSitemapEntry> entries = m_byStructureId.get(key);
        if (entries == null) {
            entries = new ArrayList<CmsInternalSitemapEntry>();
            m_byStructureId.put(key, entries);
        }
        entries.add(entry);
    }

    /**
     * @see org.opencms.cache.CmsVfsCache#flush(boolean)
     */
    @Override
    protected void flush(boolean online) {

        if (m_useMemoryMonitor && (m_online == online)) {
            m_defProps = null;
            if (m_active != null) {
                m_active.clear();
                m_active = null;
            }
        }
    }

    /**
     * Returns the subentries, including sub-sitemap lookup.<p>
     * 
     * @param cms the admin CMS context
     * @param locale the current locale
     * @param entry the entry to get the subentries for
     * 
     * @return a list of subentries
     */
    protected List<CmsInternalSitemapEntry> getSubEntries(CmsObject cms, Locale locale, CmsInternalSitemapEntry entry) {

        // check sitemap property
        String subSitemapId = entry.getProperties().get(CmsSitemapManager.Property.sitemap.name());
        if (CmsStringUtil.isEmptyOrWhitespaceOnly(subSitemapId)) {
            return entry.getSubEntries();
        }
        try {
            // switch to sub-sitemap
            CmsResource subSitemap = internalReadResource(cms, subSitemapId);
            CmsXmlSitemap sitemapXml = internalUnmarshalSitemapResource(cms, subSitemap);
            CmsSitemapBean sitemap = internalGetSitemap(cms, sitemapXml, locale);
            if (sitemap == null) {
                // be sure the entry has no sub-entries
                entry.setSubEntries(new ArrayList<CmsInternalSitemapEntry>());
                // no sitemap found
                return entry.getSubEntries();
            }
            // set the sub-entries
            entry.setSubEntries(sitemap.getSiteEntries());
            // continue with the sub-sitemap

        } catch (CmsException e) {
            LOG.error("Can't read sub-sitemap:");
            LOG.error(e.getLocalizedMessage(), e);
        }
        return entry.getSubEntries();
    }

    /**
     * Copies the internal {@link CmsObject} and sets the project of the copy to the project of another <code>CmsObject</code>.<p>
     *   
     * @param cms the <code>CmsObject</code> whose project should be used 
     * @return a copied admin <code>CmsObject</code>
     * @throws CmsException if something goes wrong 
     */
    protected CmsObject internalCreateCmsObject(CmsObject cms) throws CmsException {

        CmsObject adminCms = OpenCms.initCmsObject(m_adminCms);
        adminCms.getRequestContext().setCurrentProject(cms.getRequestContext().currentProject());
        return adminCms;
    }

    /**
     * Internal method for getting the entry point resources from the VFS.<p>
     * 
     * @param adminCms the CMS context
     *  
     * @return the list of entry point resources 
     * 
     * @throws CmsException if something goes wrong 
     */
    protected List<CmsResource> internalGetEntryPointResources(CmsObject adminCms) throws CmsException {

        List<CmsResource> entryPoints = adminCms.readResourcesWithProperty(
            "/",
            CmsPropertyDefinition.PROPERTY_ADE_SITEMAP,
            null,
            CmsResourceFilter.IGNORE_EXPIRATION.addRequireFolder());
        return entryPoints;
    }

    /**
     * Internal method for getting a sitemap bean from a {@link CmsXmlSitemap} object.<p>
     * 
     * @param adminCms the CMS context 
     * @param xmlSitemap the XML sitemap  
     * @param locale the locale for which the sitemap should be retrieved 
     * 
     * @return the sitemap 
     */
    protected CmsSitemapBean internalGetSitemap(CmsObject adminCms, CmsXmlSitemap xmlSitemap, Locale locale) {

        CmsSitemapBean locSitemap = xmlSitemap.getSitemap(adminCms, locale);
        return locSitemap;
    }

    /**
     * Internal method for reading a resource based using a string containing its structure id.<p>
     * 
     * @param cms the current CMS context 
     * @param strId a string containing a structure id 
     * @return the resource with the given id 
     * 
     * @throws CmsException if something goes wrong 
     */
    protected CmsResource internalReadResource(CmsObject cms, String strId) throws CmsException {

        CmsResource subSitemap = cms.readResource(new CmsUUID(strId));
        return subSitemap;
    }

    /**
     * Internal method for reading a sitemap file.<p>
     * 
     * @param cms the CMS context 
     * @param entryPoint the resource which is the entry point of the sitemap 
     * @param sitemapPath the VFS path of the sitemap
     * 
     * @return the sitemap file 
     * 
     * @throws CmsException if something goes wrong 
     */
    protected CmsFile internalReadSitemapFile(CmsObject cms, CmsResource entryPoint, String sitemapPath)
    throws CmsException {

        CmsFile sitemapFile;
        try {
            // interpret property value as root path
            sitemapFile = cms.readFile(sitemapPath);
        } catch (CmsVfsResourceNotFoundException e) {
            // interpret property value as site path
            sitemapPath = OpenCms.getSiteManager().getSiteForRootPath(entryPoint.getRootPath()).getSiteRoot()
                + sitemapPath;
            sitemapFile = cms.readFile(sitemapPath);
        }
        return sitemapFile;
    }

    /**
     * Internal method for reading the sitemap property of entry points.<p>
     * 
     * @param adminCms the CMS context 
     * @param entryPoint a resource which should be an entry point folder 
     * 
     * @return the sitemap property of the resource 
     * 
     * @throws CmsException if something goes wrong 
     */
    protected String internalReadSitemapProperty(CmsObject adminCms, CmsResource entryPoint) throws CmsException {

        String sitemapPath = adminCms.readPropertyObject(entryPoint, CmsPropertyDefinition.PROPERTY_ADE_SITEMAP, false).getValue();
        return sitemapPath;
    }

    /**
     * Internal method for unmarshalling a sitemap file.<p>
     * 
     * @param adminCms the CMS context 
     * @param sitemapFile the sitemap file 
     * 
     * @return the unmarshalled {@link CmsXmlSitemap} 
     * 
     * @throws CmsException if something goes wrong 
     */
    protected CmsXmlSitemap internalUnmarshalSitemapFile(CmsObject adminCms, CmsFile sitemapFile) throws CmsException {

        CmsXmlSitemap xmlSitemap = CmsXmlSitemapFactory.unmarshal(adminCms, sitemapFile);
        return xmlSitemap;
    }

    /**
     * Internal method for unmarshalling a sitemap resource.<p>
     * 
     * @param cms the current CMS context 
     * @param sitemapRes the resource which contains the sitemap 
     * @return the XML sitemap
     *  
     * @throws CmsException if something goes wrong 
     */
    protected CmsXmlSitemap internalUnmarshalSitemapResource(CmsObject cms, CmsResource sitemapRes) throws CmsException {

        CmsXmlSitemap sitemapXml = CmsXmlSitemapFactory.unmarshal(cms, sitemapRes);
        return sitemapXml;
    }

    /**
     * Registers a cached object in the memory monitor.<p>
     * 
     * This does nothing if the <code>useMemoryMonitor</code> parameter of the constructor was <code>false</code>.
     *  
     * @param key the key for registering the object in the memory monitor 
     * @param obj the object to register in the memory monitor 
     */
    protected void register(String key, Object obj) {

        if (m_useMemoryMonitor) {
            String cacheKey = CmsSitemapStructureCache.class.getName() + "." + key + "." + getName();
            OpenCms.getMemoryMonitor().register(cacheKey, obj);
        }
    }

    /**
     * @see org.opencms.cache.CmsVfsCache#uncacheResource(org.opencms.file.CmsResource)
     */
    @Override
    protected void uncacheResource(CmsResource resource) {

        if (resource == null) {
            LOG.warn(Messages.get().container(Messages.LOG_WARN_UNCACHE_NULL_0));
            return;
        }

        if (!m_useMemoryMonitor || m_online) {
            return;
        }

        // if sitemap schema changed
        if (resource.getRootPath().equals(CmsResourceTypeXmlSitemap.SCHEMA)) {
            // flush offline default properties 
            m_defProps = null;
            return;
        }

        // we care only more if the modified resource is a sitemap
        if (!CmsResourceTypeXmlSitemap.isSitemap(resource)) {
            return;
        }

        //TODO: Replace this by ??? 
        // flush all uri's
        if (m_active != null) {
            m_active.clear();
            m_active = null;
        }
    }

    /**
     * Adds the given entry and all its sub-entries recursively to the cache.<p>
     * 
     * @param cms the admin CMS context
     * @param active the active sitemap lookup table to be filled
     * @param entry the entry itself
     * @param locale the locale to visit
     * @param entryPoint the entry's point root path
     * @param isRootEntry true if the entry is a root entry of a root sitemap 
     * @param entryPos the entry's position
     * @param properties the inherited properties
     * 
     * @throws CmsException if something goes wrong 
     */
    protected void visitEntry(
        CmsObject cms,
        Map<String, String> active,
        CmsInternalSitemapEntry entry,
        Locale locale,
        String entryPoint,
        boolean isRootEntry,
        int entryPos,
        Map<String, String> properties) throws CmsException {

        // set runtime data
        String currentEntryPoint = entryPoint;
        entry.setRuntimeInfo(currentEntryPoint, entryPos, properties);
        entry.setRootEntry(isRootEntry);

        // cache
        Map<CmsUUID, CmsInternalSitemapEntry> byId = m_byId;
        Map<String, CmsInternalSitemapEntry> byPath = m_byUri;
        byId.put(entry.getId(), entry);
        byPath.put(locale.toString() + entry.getRootPath(), entry);
        m_pathSet.add(entry.getRootPath());

        // collect the inherited properties
        properties.putAll(entry.getProperties());
        CmsUUID structureId = entry.getStructureId();
        CmsResource resource = cms.readResource(structureId);
        addEntryForStructureId(structureId, locale, entry);
        addEntryForRootPath(resource.getRootPath(), locale, entry);

        // get sub-entries
        List<CmsInternalSitemapEntry> subEntries = getSubEntries(cms, locale, entry);
        String sitemapUuid = properties.get(CmsSitemapManager.Property.sitemap.name());
        try {
            if (sitemapUuid != null) {
                CmsResource sitemapResource = internalReadResource(cms, sitemapUuid);
                // collect sitemap
                active.put(locale.toString() + entry.getRootPath(), sitemapResource.getRootPath());
                // be sure the sub-entries do not inherit the sitemap property
                properties.remove(CmsSitemapManager.Property.sitemap.name());
                // be sure to set the right entry point
                currentEntryPoint = entry.getRootPath();
            }
        } catch (CmsException e) {
            // Sub-sitemap couldn't be read, but we want to proceed with reading the rest of the sitemap
            LOG.error("Can't read sub-sitemap: ");
            LOG.error(e.getLocalizedMessage(), e);
        }
        int size = subEntries.size();
        for (int position = 0; position < size; position++) {
            // visit sub-entries
            CmsInternalSitemapEntry subEntry = subEntries.get(position);
            visitEntry(cms, active, subEntry, locale, currentEntryPoint, false, position, properties);
        }
    }

    /**
     * Visits a root sitemap.<p>
     * 
     * @param cms the CMS context 
     * @param active the map of active sitemaps 
     * @param entryPoint the entry point resource 
     * @param sitemapFile the file containing the sitemap 
     * @param xmlSitemap the sitemap XML structure 
     * 
     * @throws CmsException if something goes wrong
     */
    protected void visitRootSitemap(
        CmsObject cms,
        Map<String, String> active,
        CmsResource entryPoint,
        CmsFile sitemapFile,
        CmsXmlSitemap xmlSitemap) throws CmsException {

        for (Locale locale : xmlSitemap.getLocales()) {
            // entry point sitemaps can have several locales
            active.put(locale.toString() + entryPoint.getRootPath(), sitemapFile.getRootPath());
            CmsSitemapBean locSitemap = internalGetSitemap(cms, xmlSitemap, locale);
            // root sitemaps must have one and only one root entry
            CmsInternalSitemapEntry startEntry = locSitemap.getSiteEntries().get(0);
            if (CmsStringUtil.isNotEmptyOrWhitespaceOnly(startEntry.getName())) {
                // Root entries of root sitemaps HAVE to have an empty name
                startEntry.removeName();
            }
            // inherited properties 
            // we can safely use one reference, since CmsSiteEntryBean#setRuntimeInfo(...) will clone it      
            Map<String, String> properties = new HashMap<String, String>();

            // start iterating
            visitEntry(cms, active, startEntry, locale, entryPoint.getRootPath(), true, 0, properties);
        }
    }

    /**
     * Initializes the caches.<p>
     * 
     * @param memMonitor the memory monitor instance
     */
    private void initCaches(CmsMemoryMonitor memMonitor) {

        m_byUri = new HashMap<String, CmsInternalSitemapEntry>();
        register("uris", m_byUri);

        m_byId = new HashMap<CmsUUID, CmsInternalSitemapEntry>();
        register("ids", m_byId);

        m_pathSet = new HashSet<String>();
        register("pathSet", m_pathSet);

        m_byStructureId = new HashMap<CmsPair<CmsUUID, Locale>, List<CmsInternalSitemapEntry>>();
        register("structIds", m_byStructureId);

        m_byRootVfsPath = new HashMap<CmsPair<String, Locale>, List<CmsInternalSitemapEntry>>();
        register("rootPaths", m_byRootVfsPath);

        m_siteRoots = new HashSet<String>();
        register("siteRoots", m_siteRoots);

    }

}
