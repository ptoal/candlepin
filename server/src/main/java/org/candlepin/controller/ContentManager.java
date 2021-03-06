/**
 * Copyright (c) 2009 - 2016 Red Hat, Inc.
 *
 * This software is licensed to you under the GNU General Public License,
 * version 2 (GPLv2). There is NO WARRANTY for this software, express or
 * implied, including the implied warranties of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
 * along with this software; if not, see
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
 *
 * Red Hat trademarks are not licensed under GPLv2. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package org.candlepin.controller;

import org.candlepin.model.Content;
import org.candlepin.model.ContentCurator;
import org.candlepin.model.Owner;
import org.candlepin.model.OwnerContent;
import org.candlepin.model.OwnerContentCurator;
import org.candlepin.model.Product;
import org.candlepin.model.ProductCurator;
import org.candlepin.model.dto.ContentData;
import org.candlepin.model.dto.ProductData;
import org.candlepin.model.dto.ProductContentData;
import org.candlepin.util.Traceable;
import org.candlepin.util.TraceableParam;

import com.google.inject.Inject;
import com.google.inject.persist.Transactional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;



/**
 * The ContentManager class provides methods for creating, updating and removing content instances
 * which also perform the cleanup and general maintenance necessary to keep content state in sync
 * with other objects which reference them.
 * <p></p>
 * The methods provided by this class are the prefered methods to use for CRUD operations on
 * content, to ensure content versioning and linking is handled properly.
 */
public class ContentManager {
    private static Logger log = LoggerFactory.getLogger(ContentManager.class);

    private ContentCurator contentCurator;
    private EntitlementCertificateGenerator entitlementCertGenerator;
    private OwnerContentCurator ownerContentCurator;
    private ProductCurator productCurator;
    private ProductManager productManager;

    @Inject
    public ContentManager(
        ContentCurator contentCurator, EntitlementCertificateGenerator entitlementCertGenerator,
        OwnerContentCurator ownerContentCurator, ProductCurator productCurator,
        ProductManager productManager) {

        this.contentCurator = contentCurator;
        this.entitlementCertGenerator = entitlementCertGenerator;
        this.ownerContentCurator = ownerContentCurator;
        this.productCurator = productCurator;
        this.productManager = productManager;
    }

    /**
     * Creates a new Content for the given owner, using the data in the provided DTO.
     *
     * @param contentData
     *  A content DTO representing the content to create
     *
     * @param owner
     *  The owner for which to create the content
     *
     * @throws IllegalArgumentException
     *  if contentData is null or incomplete, or owner is null
     *
     * @throws IllegalStateException
     *  if the contentData represents content that already exists
     *
     * @return
     *  a new Content instance representing the specified content for the given owner
     */
    public Content createContent(ContentData contentData, Owner owner) {
        if (contentData == null) {
            throw new IllegalArgumentException("contentData is null");
        }

        if (contentData.getId() == null || contentData.getType() == null || contentData.getLabel() == null ||
            contentData.getName() == null || contentData.getVendor() == null) {
            throw new IllegalArgumentException("contentData is incomplete");
        }

        if (this.ownerContentCurator.contentExists(owner, contentData.getId())) {
            throw new IllegalStateException("content has already been created: " + contentData.getId());
        }

        // TODO: more validation here...?

        Content entity = new Content(contentData.getId());
        this.applyContentChanges(entity, contentData);

        log.debug("Creating new content for org: {}, {}", entity, owner);

        // Check if we have an alternate version we can use instead.
        List<Content> alternateVersions = this.ownerContentCurator.getContentByVersions(
            owner, Collections.<String, Integer>singletonMap(entity.getId(), entity.getEntityVersion()))
            .list();

        log.debug("Checking {} alternate content versions", alternateVersions.size());
        for (Content alt : alternateVersions) {
            if (alt.equals(entity)) {
                // If we're "creating" a content, we shouldn't have any other object references to
                // update for this content. Instead, we'll just add the new owner to the content.
                this.ownerContentCurator.mapContentToOwner(alt, owner);
                return alt;
            }
        }

        entity = this.contentCurator.create(entity);
        this.ownerContentCurator.mapContentToOwner(entity, owner);

        return entity;
    }

    /**
     * Updates the specified content instance, creating a new version of the content as necessary.
     * The content instance returned by this method is not guaranteed to be the same instance passed
     * in. As such, once this method has been called, callers should only use the instance output by
     * this method.
     *
     * @param owner
     *  The owner for which to update the content
     *
     * @param regenerateEntitlementCerts
     *  Whether or not changes made to the content should trigger the regeneration of entitlement
     *  certificates for affected consumers
     *
     * @throws IllegalStateException
     *  if the given content update references a content that does not exist for the specified owner
     *
     * @throws IllegalArgumentException
     *  if either the provided content entity or owner are null
     *
     * @return
     *  the updated content entity, or a new content entity
     */
    @Transactional
    public Content updateContent(ContentData update, Owner owner, boolean regenerateEntitlementCerts) {
        if (update == null) {
            throw new IllegalArgumentException("update is null");
        }

        if (update.getId() == null) {
            throw new IllegalArgumentException("update is incomplete");
        }

        if (owner == null) {
            throw new IllegalArgumentException("owner is null");
        }

        // Resolve the entity to ensure we're working with the merged entity, and to ensure it's
        // already been created.

        // TODO: FIXME:
        // There's a bug here where if changes are applied to an entity's collections, and then
        // this method is called, the check below will cause the changes to be persisted.
        // This needs to be re-written to use DTOs as the primary source of entity creation, rather
        // than a bolted-on utility method.
        // If we never edit the entity directly, however, this is safe.

        Content entity = this.ownerContentCurator.getContentById(owner, update.getId());

        if (entity == null) {
            // If we're doing an exclusive update, this should be an error condition
            throw new IllegalStateException("Content has not yet been created");
        }

        // Make sure we actually have a change to apply
        if (!entity.isChangedBy(update)) {
            return entity;
        }

        log.debug("Applying content update for org: {}, {}", entity, owner);
        Content updated = this.applyContentChanges((Content) entity.clone(), update);

        List<Content> alternateVersions = this.ownerContentCurator.getContentByVersions(
            owner, Collections.<String, Integer>singletonMap(updated.getId(), updated.getEntityVersion()))
            .list();

        log.debug("Checking {} alternate content versions", alternateVersions.size());
        for (Content alt : alternateVersions) {
            if (alt.equals(updated)) {
                log.debug("Converging product with existing: {} => {}", updated, alt);

                // Make sure every product using the old version/entity are updated to use the new one
                List<Product> affectedProducts = this.productCurator
                    .getProductsByContent(owner, Arrays.asList(updated.getId()))
                    .list();

                this.ownerContentCurator.updateOwnerContentReferences(owner,
                    Collections.<String, String>singletonMap(entity.getUuid(), alt.getUuid()));

                log.debug("Updating {} affected products", affectedProducts.size());
                ContentData cdata = updated.toDTO();

                for (Product product : affectedProducts) {
                    ProductData pdata = product.toDTO();
                    log.debug("Updating affected product: {}", product);

                    // We're taking advantage of the mutable nature of our joining objects.
                    // Probably not the best idea for long-term maintenance, but it works for now.
                    ProductContentData pcd = pdata.getProductContent(updated.getId());
                    if (pcd != null) {
                        pcd.setContent(cdata);

                        // Impl note: This should also take care of our entitlement cert regeneration
                        this.productManager.updateProduct(pdata, owner, regenerateEntitlementCerts);
                    }
                }

                return alt;
            }
        }

        // Temporarily (?) disabled. If we ever move to clustered caching (rather than per-instance
        // caching, this branch should be re-enabled.
        /*
        // No alternate versions with which to converge. Check if we can do an in-place update instead
        if (this.ownerContentCurator.getOwnerCount(updated) < 2) {
            log.debug("Applying in-place update to content: {}", updated);

            updated = this.contentCurator.merge(this.applyContentChanges(entity, update, owner));

            if (regenerateEntitlementCerts) {
                // Every owner with a pool using any of the affected products needs an update.
                List<Product> affectedProducts = this.productCurator
                    .getProductsByContent(Arrays.asList(updated.getUuid()))
                    .list();

                this.entitlementCertGenerator.regenerateCertificatesOf(
                    Arrays.asList(owner), affectedProducts, true
                );
            }

            return updated;
        }
        */

        log.debug("Forking content and applying update: {}", updated);

        // Get products that currently use this content...
        List<Product> affectedProducts = this.productCurator
            .getProductsByContent(owner, Arrays.asList(updated.getId()))
            .list();

        // Clear the UUID so Hibernate doesn't think our copy is a detached entity
        updated.setUuid(null);
        updated = this.contentCurator.create(updated);

        this.ownerContentCurator.updateOwnerContentReferences(owner,
            Collections.<String, String>singletonMap(entity.getUuid(), updated.getUuid()));

        // Impl note:
        // This block is a consequence of products and contents not being strongly related.
        log.debug("Updating affected products");

        ContentData cdata = updated.toDTO();

        for (Product product : affectedProducts) {
            log.debug("Updating affected product: {}", product);

            // We're taking advantage of the mutable nature of our joining objects.
            // Probably not the best idea for long-term maintenance, but it works for now.
            ProductData pdata = product.toDTO();
            ProductContentData pcd = pdata.getProductContent(updated.getId());
            if (pcd != null) {
                pcd.setContent(cdata);

                // Impl note: This should also take care of our entitlement cert regeneration
                this.productManager.updateProduct(pdata, owner, regenerateEntitlementCerts);
            }
        }

        return updated;
    }

    /**
     * Creates or updates content from the given content DTOs, omitting product updates for the
     * provided Red Hat product IDs.
     * <p></p>
     * The content DTOs provided in the given map should be mapped by the content's Red Hat ID. If
     * the mappings are incorrect or inconsistent, the result of this method is undefined.
     *
     * @param owner
     *  The owner for which to import the given content
     *
     * @param contentData
     *  A mapping of Red Hat content ID to content DTOs to import
     *
     * @param importedProductIds
     *  A set of Red Hat product IDs specifying products which are being imported and should not be
     *  updated as part of this import operation
     *
     * @return
     *  A mapping of Red Hat content ID to content entities representing the imported content
     */
    @SuppressWarnings("checkstyle:methodlength")
    @Transactional
    @Traceable
    public ImportResult<Content> importContent(@TraceableParam("owner") Owner owner,
        Map<String, ContentData> contentData, Set<String> importedProductIds) {

        if (owner == null) {
            throw new IllegalArgumentException("owner is null");
        }

        ImportResult<Content> importResult = new ImportResult<Content>();

        if (contentData == null || contentData.isEmpty()) {
            // Nothing to import
            return importResult;
        }

        Map<String, Content> skippedContent = importResult.getSkippedEntities();
        Map<String, Content> createdContent = importResult.getCreatedEntities();
        Map<String, Content> updatedContent = importResult.getUpdatedEntities();

        Map<String, Integer> contentVersions = new HashMap<String, Integer>();
        Map<String, Content> sourceContent = new HashMap<String, Content>();
        Map<String, List<Content>> existingVersions = new HashMap<String, List<Content>>();
        List<OwnerContent> ownerContentBuffer = new LinkedList<OwnerContent>();

        // - Divide imported products into sets of updates and creates
        log.debug("Fetching existing content for update...");
        for (Content content : this.ownerContentCurator.getContentByIds(owner, contentData.keySet())) {
            ContentData update = contentData.get(content.getId());

            if (!content.isChangedBy(update)) {
                // This content won't be changing, so we'll just pretend it's not being imported at all
                skippedContent.put(content.getId(), content);
                continue;
            }

            // Content is coming from an upstream source; lock it so only upstream can make
            // further changes to it. If we ever use this method for anything other than
            // imports, we'll need to stop doing this.
            sourceContent.put(content.getId(), content);
            content = this.applyContentChanges((Content) content.clone(), update);

            updatedContent.put(content.getId(), content);
            contentVersions.put(content.getId(), content.getEntityVersion());
        }

        log.debug("Validating new content...");
        for (ContentData update : contentData.values()) {
            if (!skippedContent.containsKey(update.getId()) && !updatedContent.containsKey(update.getId())) {
                // Ensure content is minimally populated
                if (update.getId() == null || update.getType() == null || update.getLabel() == null ||
                    update.getName() == null || update.getVendor() == null) {
                    throw new IllegalStateException("Content data is incomplete: " + update);
                }

                Content content = this.applyContentChanges(new Content(update.getId()), update);

                createdContent.put(content.getId(), content);
                contentVersions.put(content.getId(), content.getEntityVersion());
            }
        }

        log.debug("Checking for existing content versions...");
        for (Content alt : this.ownerContentCurator.getContentByVersions(owner, contentVersions)) {
            List<Content> alternates = existingVersions.get(alt.getId());
            if (alternates == null) {
                alternates = new LinkedList<Content>();
                existingVersions.put(alt.getId(), alternates);
            }

            alternates.add(alt);
        }

        contentVersions.clear();
        contentVersions = null;

        // We're about to start modifying the maps, so we need to clone the created set before we
        // start adding the update forks to it.
        Map<String, Content> stagedEntities = new HashMap<String, Content>(createdContent);

        // Process the created group...
        // Check our created set for existing versions:
        //  - If there's an existing version, we'll remove the staged entity from the creation
        //    set, and stage an owner-content mapping for the existing version
        //  - Otherwise, we'll stage the new entity for persistence by leaving it in the created
        //    set, and stage an owner-content mapping to the new entity
        Iterator<Content> iterator = stagedEntities.values().iterator();
        createdContentLoop: while (iterator.hasNext()) {
            Content created = iterator.next();
            List<Content> alternates = existingVersions.get(created.getId());

            if (alternates != null) {
                for (Content alt : alternates) {
                    if (created.equals(alt)) {
                        ownerContentBuffer.add(new OwnerContent(owner, alt));
                        createdContent.put(alt.getId(), alt);
                        iterator.remove();

                        continue createdContentLoop;
                    }
                }
            }

            ownerContentBuffer.add(new OwnerContent(owner, created));
        }

        // Process the updated group...
        // Check our updated set for existing versions:
        //  - If there's an existing versions, we'll update the update set to point to the existing
        //    version
        //  - Otherwise, we need to stage the updated entity for persistence
        updatedContentLoop: for (Map.Entry<String, Content> entry : updatedContent.entrySet()) {
            Content updated = entry.getValue();
            List<Content> alternates = existingVersions.get(updated.getId());
            if (alternates != null) {
                for (Content alt : alternates) {
                    if (!updated.getUuid().equals(alt.getUuid()) && updated.equals(alt)) {
                        updated = alt;
                        entry.setValue(alt);

                        continue updatedContentLoop;
                    }
                }
            }

            // We need to stage the updated entity for persistence. We'll reuse the now-empty
            // createdContent map for this.
            updated.setUuid(null);
            stagedEntities.put(updated.getId(), updated);
        }

        // Persist our staged entities
        // We probably don't want to evict the content yet, as they'll appear as unmanaged if
        // they're used later. However, the join objects can be evicted safely since they're only
        // really used here.
        log.debug("Persisting content changes...");
        this.contentCurator.saveAll(stagedEntities.values(), true, false);
        this.ownerContentCurator.saveAll(ownerContentBuffer, true, true);

        // Fetch collection of products affected by this import that aren't being imported themselves
        log.debug("Updating non-imported, affected products...");
        List<Product> affectedProducts = this.productCurator
            .getProductsByContent(owner, sourceContent.keySet(), importedProductIds)
            .list();

        if (affectedProducts != null && !affectedProducts.isEmpty()) {
            // Get the collection of content those products use
            Map<String, Content> affectedProductsContent = new HashMap<String, Content>();
            for (Content content : this.contentCurator.getContentByProducts(affectedProducts)) {
                affectedProductsContent.put(content.getId(), content);
            }

            // Update the content map so it references the updated content
            affectedProductsContent.putAll(updatedContent);

            Map<String, ProductData> affectedProductData = new HashMap<String, ProductData>();
            for (Product product : affectedProducts) {
                ProductData productData = product.toDTO();

                for (ProductContentData pcd : productData.getProductContent()) {
                    ContentData cdata = pcd.getContent();
                    Content content = updatedContent.get(cdata.getId());

                    if (content != null) {
                        // We're taking advantage of the mutable nature of our joining objects.
                        // Probably not the best idea for long-term maintenance, but it works for now.
                        pcd.setContent(content.toDTO());
                    }
                }

                affectedProductData.put(productData.getId(), productData);
            }

            // Perform a micro-import for these products using the content map we just built
            this.productManager.importProducts(owner, affectedProductData, affectedProductsContent);
        }

        // Perform bulk reference update
        Map<String, String> contentUuidMap = new HashMap<String, String>();
        for (Content update : updatedContent.values()) {
            Content source = sourceContent.get(update.getId());

            contentUuidMap.put(source.getUuid(), update.getUuid());
        }

        this.ownerContentCurator.updateOwnerContentReferences(owner, contentUuidMap);

        // Return
        return importResult;
    }

    /**
     * Removes the specified content from the given owner.
     *
     * @param owner
     *  The owner for which to remove the content
     *
     * @param entity
     *  The content entity to remove
     *
     * @param regenerateEntitlementCerts
     *  Whether or not changes made to the content should trigger the regeneration of entitlement
     *  certificates for affected consumers
     *
     * @throws IllegalStateException
     *  if this method is called with an entity does not exist in the backing database for the given
     *  owner
     *
     * @throws IllegalArgumentException
     *  if entity or owner is null
     */
    @Transactional
    public void removeContent(Owner owner, Content entity, boolean regenerateEntitlementCerts) {
        if (owner == null) {
            throw new IllegalArgumentException("owner is null");
        }

        if (entity == null) {
            throw new IllegalArgumentException("entity is null");
        }

        // This has to fetch a new instance, or we'll be unable to compare the objects
        Content existing = this.ownerContentCurator.getContentById(owner, entity.getId());
        if (existing == null) {
            // If we're doing an exclusive update, this should be an error condition
            throw new IllegalStateException("Content has not yet been created");
        }

        this.removeContentByUuids(owner, Arrays.asList(existing.getUuid()), regenerateEntitlementCerts);
    }

    /**
     * Removes the specified content from the given owner.
     *
     * @param owner
     *  The owner for which to remove the content
     *
     * @param content
     *  The content entity to remove
     *
     * @param regenerateEntitlementCerts
     *  Whether or not changes made to the content should trigger the regeneration of entitlement
     *  certificates for affected consumers
     *
     * @throws IllegalArgumentException
     *  if entity or owner is null
     */
    public void removeContent(Owner owner, Collection<Content> content, boolean regenerateEntitlementCerts) {
        if (content != null && !content.isEmpty()) {
            Map<String, Content> contentMap = new HashMap<String, Content>();
            for (Content entity : content) {
                contentMap.put(entity.getUuid(), entity);
            }

            this.removeContentByUuids(owner, contentMap.keySet(), regenerateEntitlementCerts);
        }
    }

    /**
     * Removes all content from the given owner.
     *
     * @param owner
     *  The owner from which to remove content
     *
     * @param regenerateEntitlementCerts
     *  Whether or not changes made to the content should trigger the regeneration of entitlement
     *  certificates for affected consumers
     *
     * @throws IllegalArgumentException
     *  if owner is null
     */
    public void removeAllContent(Owner owner, boolean regenerateEntitlementCerts) {
        this.removeContentByUuids(owner, this.ownerContentCurator.getContentUuidsByOwner(owner),
            regenerateEntitlementCerts);
    }

    /**
     * Removes all content with the provided UUIDs from the given owner.
     *
     * @param owner
     *  The owner from which to remove content
     *
     * @param contentUuids
     *  A collection of UUIDs representing the content to remove
     *
     * @param regenerateEntitlementCerts
     *  Whether or not changes made to the content should trigger the regeneration of entitlement
     *  certificates for affected consumers
     *
     * @throws IllegalArgumentException
     *  if owner is null
     */
    public void removeContentByUuids(Owner owner, Collection<String> contentUuids,
        boolean regenerateEntitlementCerts) {

        if (owner == null) {
            throw new IllegalArgumentException("owner is null");
        }

        if (contentUuids != null && !contentUuids.isEmpty()) {
            log.debug("Deleting content with UUIDs: {}", contentUuids);

            List<Product> affectedProducts = this.productCurator
                .getProductsByContentUuids(owner, contentUuids)
                .list();

            if (!affectedProducts.isEmpty()) {
                log.debug("Updating {} affected products", affectedProducts.size());

                if (!(contentUuids instanceof Set)) {
                    // Convert this to a set so our filtering lookups aren't painfully slow
                    contentUuids = new HashSet<String>(contentUuids);
                }

                // Get the collection of content those products use, throwing out the ones we'll be
                // removing shortly
                Map<String, Content> affectedProductsContent = new HashMap<String, Content>();
                for (Content content : this.contentCurator.getContentByProducts(affectedProducts)) {
                    if (!contentUuids.contains(content.getUuid())) {
                        affectedProductsContent.put(content.getId(), content);
                    }
                }

                // Convert our affectedProducts into DTOs (hoping Hibernate uses its entity cache
                // instead of pulling down the content list for each product...)
                Map<String, ProductData> affectedProductData = new HashMap<String, ProductData>();
                for (Product product : affectedProducts) {
                    ProductData pdata = product.toDTO();

                    Iterator<ProductContentData> pcd = pdata.getProductContent().iterator();
                    while (pcd.hasNext()) {
                        ContentData cdata = pcd.next().getContent();

                        if (!affectedProductsContent.containsKey(cdata.getId())) {
                            pcd.remove();
                        }
                    }

                    affectedProductData.put(pdata.getId(), pdata);
                }

                // Perform a micro-import for these products using the content map we just built
                log.debug("Performing micro-import for products: {}", affectedProductData);
                this.productManager.importProducts(owner, affectedProductData, affectedProductsContent);

                if (regenerateEntitlementCerts) {
                    this.entitlementCertGenerator.regenerateCertificatesOf(
                        Arrays.asList(owner), affectedProducts, true);
                }
            }

            // Remove content references
            this.ownerContentCurator.removeOwnerContentReferences(owner, contentUuids);
        }
    }

    /**
     * Applies the changes from the given DTO to the specified entity
     *
     * @param entity
     *  The entity to modify
     *
     * @param update
     *  The DTO containing the modifications to apply
     *
     * @throws IllegalArgumentException
     *  if entity, update or owner is null
     *
     * @return
     *  The updated product entity
     */
    private Content applyContentChanges(Content entity, ContentData update) {
        if (entity == null) {
            throw new IllegalArgumentException("entity is null");
        }

        if (update == null) {
            throw new IllegalArgumentException("update is null");
        }

        if (update.getType() != null) {
            entity.setType(update.getType());
        }

        if (update.getLabel() != null) {
            entity.setLabel(update.getLabel());
        }

        if (update.getName() != null) {
            entity.setName(update.getName());
        }

        if (update.getVendor() != null) {
            entity.setVendor(update.getVendor());
        }

        if (update.getContentUrl() != null) {
            entity.setContentUrl(update.getContentUrl());
        }

        if (update.getRequiredTags() != null) {
            entity.setRequiredTags(update.getRequiredTags());
        }

        if (update.getReleaseVersion() != null) {
            entity.setReleaseVersion(update.getReleaseVersion());
        }

        if (update.getGpgUrl() != null) {
            entity.setGpgUrl(update.getGpgUrl());
        }

        if (update.getMetadataExpire() != null) {
            entity.setMetadataExpire(update.getMetadataExpire());
        }

        if (update.getModifiedProductIds() != null) {
            entity.setModifiedProductIds(update.getModifiedProductIds());
        }

        if (update.getArches() != null) {
            entity.setArches(update.getArches());
        }

        if (update.isLocked() != null) {
            entity.setLocked(update.isLocked());
        }

        return entity;
    }

}
