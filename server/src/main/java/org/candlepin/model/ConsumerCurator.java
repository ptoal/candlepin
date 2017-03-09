/**
 * Copyright (c) 2009 - 2012 Red Hat, Inc.
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
package org.candlepin.model;

import org.candlepin.common.config.Configuration;
import org.candlepin.common.exceptions.BadRequestException;
import org.candlepin.common.exceptions.NotFoundException;
import org.candlepin.common.paging.Page;
import org.candlepin.common.paging.PageRequest;
import org.candlepin.resteasy.parameter.KeyValueParameter;
import org.candlepin.util.FactValidator;
import org.candlepin.util.Util;

import com.google.inject.Inject;
import com.google.inject.persist.Transactional;

import org.hibernate.Criteria;
import org.hibernate.EntityMode;
import org.hibernate.HibernateException;
import org.hibernate.Query;
import org.hibernate.ReplicationMode;
import org.hibernate.criterion.CriteriaQuery;
import org.hibernate.criterion.Criterion;
import org.hibernate.criterion.Disjunction;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Projections;
import org.hibernate.criterion.Restrictions;
import org.hibernate.engine.spi.TypedValue;
import org.hibernate.internal.util.StringHelper;
import org.hibernate.type.CompositeType;
import org.hibernate.type.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.Map.Entry;

import javax.persistence.LockModeType;



/**
 * ConsumerCurator
 */
public class ConsumerCurator extends AbstractHibernateCurator<Consumer> {
    private static Logger log = LoggerFactory.getLogger(ConsumerCurator.class);
    private static final int MAX_IN_QUERY_LENGTH = 500;

    @Inject private EntitlementCurator entitlementCurator;
    @Inject private ConsumerTypeCurator consumerTypeCurator;
    @Inject private DeletedConsumerCurator deletedConsumerCurator;
    @Inject private Configuration config;

    @Inject private FactValidator factValidator;

    public ConsumerCurator() {
        super(Consumer.class);
    }

    @Transactional
    @Override
    public Consumer create(Consumer entity) {
        entity.ensureUUID();
        this.validateFacts(entity);

        return super.create(entity);
    }

    @Transactional
    public void delete(Consumer entity) {
        // save off the IDs before we delete
        DeletedConsumer dc = new DeletedConsumer(entity.getUuid(),
            entity.getOwner().getId(), entity.getOwner().getKey(),
            entity.getOwner().getDisplayName());

        super.delete(entity);

        DeletedConsumer existing = deletedConsumerCurator.findByConsumerUuid(dc.getConsumerUuid());
        if (existing != null) {
            // update the owner ID in case the same UUID was specified by two owners
            existing.setOwnerId(dc.getOwnerId());
            existing.setOwnerKey(dc.getOwnerKey());
            existing.setOwnerDisplayName(dc.getOwnerDisplayName());
            existing.setUpdated(new Date());
            deletedConsumerCurator.save(existing);
        }
        else {
            deletedConsumerCurator.create(dc);
        }
    }

    @Transactional
    public Consumer replicate(Consumer consumer) {
        for (Entitlement entitlement : consumer.getEntitlements()) {
            entitlement.setConsumer(consumer);
        }

        ConsumerType consumerType = consumerTypeCurator.lookupByLabel(consumer
            .getType().getLabel());
        consumer.setType(consumerType);

        IdentityCertificate idCert = consumer.getIdCert();
        this.currentSession().replicate(idCert.getSerial(),
            ReplicationMode.EXCEPTION);
        this.currentSession().replicate(idCert, ReplicationMode.EXCEPTION);

        this.currentSession().replicate(consumer, ReplicationMode.EXCEPTION);

        return consumer;
    }

    /**
     * Lookup consumer by its name
     *
     * @param name consumer name to find
     * @return Consumer whose name matches the given name, null otherwise.
     */
    @Transactional
    public Consumer findByName(Owner o, String name) {
        return (Consumer) createSecureCriteria()
            .add(Restrictions.eq("name", name))
            .add(Restrictions.eq("owner", o))
            .uniqueResult();
    }

    /**
     * Lookup consumer by its virt.uuid.
     *
     * In some cases the hypervisor will report UUIDs with uppercase, while the guest will
     * report lowercase. As such we do case insensitive comparison when looking these up.
     *
     * @param uuid consumer virt.uuid to find
     * @return Consumer whose name matches the given virt.uuid, null otherwise.
     */
    @Transactional
    public Consumer findByVirtUuid(String uuid, String ownerId) {
        Consumer result = null;
        List<String> possibleGuestIds = Util.getPossibleUuids(uuid);

        String sql = "select cp_consumer.id from cp_consumer " +
            "inner join cp_consumer_facts " +
            "on cp_consumer.id = cp_consumer_facts.cp_consumer_id " +
            "where cp_consumer_facts.mapkey = 'virt.uuid' and " +
            "lower(cp_consumer_facts.element) in (:guestids) " +
            "and cp_consumer.owner_id = :ownerid " +
            "order by cp_consumer.updated desc";

        Query q = currentSession().createSQLQuery(sql);
        q.setParameterList("guestids", possibleGuestIds);
        q.setParameter("ownerid", ownerId);
        List<String> options = q.list();

        if (options != null && options.size() != 0) {
            result = this.find(options.get(0));
        }

        return result;
    }

    /**
     * Lookup all consumers matching the given guest IDs.
     *
     * Maps guest ID to the most recent registered consumer that matches it.
     * Any guest ID not found will not return null.
     *
     * If multiple registered consumers report this guest ID (re-registraiton), only the
     * most recently updated will be returned.
     *
     * @param guestId
     *
     * @return VirtConsumerMap of guest ID to it's registered guest consumer, or null if
     * none exists.
     */
    @Transactional
    public VirtConsumerMap getGuestConsumersMap(Owner owner,
            Set<String> guestIds) {
        VirtConsumerMap guestConsumersMap = new VirtConsumerMap();
        if (guestIds.size() == 0) {
            return guestConsumersMap;
        }

        List<String> possibleGuestIds = Util.getPossibleUuids(guestIds.toArray(
                new String [guestIds.size()]));

        String sql = "select cp_consumer.uuid from cp_consumer " +
            "inner join cp_consumer_facts " +
            "on cp_consumer.id = cp_consumer_facts.cp_consumer_id " +
            "where cp_consumer_facts.mapkey = 'virt.uuid' and " +
            "lower(cp_consumer_facts.element) in (:guestids) " +
            "and cp_consumer.owner_id = :ownerid " +
            "order by cp_consumer.updated desc";

        // We need to filter down to only the most recently registered consumer with
        // each guest ID.

        Query q = currentSession().createSQLQuery(sql);
        List<String> consumerUuids = new ArrayList<String>();

        int fromIndex = 0;
        int toIndex = fromIndex + MAX_IN_QUERY_LENGTH;

        while (fromIndex < possibleGuestIds.size()) {
            if (toIndex > possibleGuestIds.size()) {
                toIndex = possibleGuestIds.size();
            }
            List<String> subList = possibleGuestIds.subList(fromIndex, toIndex);
            q.setParameterList("guestids", subList);
            q.setParameter("ownerid", owner.getId());
            consumerUuids.addAll(q.list());
            fromIndex = toIndex;
            toIndex += MAX_IN_QUERY_LENGTH;
        }

        if (consumerUuids == null || consumerUuids.size() == 0) {
            return guestConsumersMap;
        }

        List<Consumer> guestConsumersWithDupes = findByUuidsAndOwner(consumerUuids, owner);
        // At this point we might have duplicates for re-registered consumers:
        for (Consumer c : guestConsumersWithDupes) {
            String virtUuid = c.getFact("virt.uuid").toLowerCase();
            if (guestConsumersMap.get(virtUuid) == null) {
                // Store both big and little endian forms in the result:
                guestConsumersMap.add(virtUuid, c);
            }

            // Can safely ignore if already in the map, this would be another consumer
            // reporting the same guest ID (re-registration), but we already sorted by
            // last update time.
        }

        return guestConsumersMap;
    }

    /**
     * Candlepin supports the notion of a user being a consumer. When in effect
     * a consumer will exist in the system who is tied to a particular user.
     *
     * @param user User
     * @return Consumer for this user if one exists, null otherwise.
     */
    @Transactional
    public Consumer findByUser(User user) {
        ConsumerType person = consumerTypeCurator
            .lookupByLabel(ConsumerType.ConsumerTypeEnum.PERSON.getLabel());
        return (Consumer) createSecureCriteria()
            .add(Restrictions.eq("username", user.getUsername()))
            .add(Restrictions.eq("type", person)).uniqueResult();
    }

    /**
     * Lookup the Consumer by its UUID.
     *
     * @param uuid Consumer UUID sought.
     * @return Consumer whose UUID matches the given value, or null otherwise.
     */
    @Transactional
    public Consumer findByUuid(String uuid) {
        return getConsumer(uuid);
    }

    /**
     * Apply a SELECT FOR UPDATE on a consumer.
     *
     * Note this method is not transactional.  It is meant to be used within
     * a larger transaction.  Starting a transaction, running a select for update,
     * and then ending the transaction is pointless.
     *
     * @return A consumer locked in the database
     */
    public Consumer lockAndLoad(Consumer c) {
        getEntityManager().lock(c, LockModeType.PESSIMISTIC_WRITE);
        return c;
    }

    @Transactional
    public List<Consumer> findByUuids(Collection<String> uuids) {
        return listByCriteria(
            createSecureCriteria().add(Restrictions.in("uuid", uuids)));
    }

    @Transactional
    public List<Consumer> findByUuidsAndOwner(Collection<String> uuids, Owner owner) {
        Criteria criteria = currentSession().createCriteria(Consumer.class);
        criteria.add(Restrictions.eq("owner", owner));
        criteria.add(Restrictions.in("uuid", uuids));
        return listByCriteria(criteria);
    }

    // NOTE: This is a giant hack that is for use *only* by SSLAuth in order
    // to bypass the authentication. Do not call it!
    // TODO: Come up with a better way to do this!
    public Consumer getConsumer(String uuid) {
        return (Consumer) createSecureCriteria()
            .add(Restrictions.eq("uuid", uuid)).uniqueResult();
    }

    @SuppressWarnings("unchecked")
    @Transactional
    public List<Consumer> listByOwner(Owner owner) {
        return createSecureCriteria()
            .add(Restrictions.eq("owner", owner)).list();
    }

    /**
     * Search for Consumers with fields matching those provided.
     *
     * @param userName the username to match, or null to ignore
     * @param types the types to match, or null/empty to ignore
     * @param owner Optional owner to filter on, pass null to skip.
     * @return a list of matching Consumers
     */
    @SuppressWarnings("unchecked")
    @Transactional
    public Page<List<Consumer>> listByUsernameAndType(String userName,
        List<ConsumerType> types, Owner owner, PageRequest pageRequest) {

        Criteria criteria = createSecureCriteria();

        if (userName != null) {
            criteria.add(Restrictions.eq("username", userName));
        }
        if (types != null && !types.isEmpty()) {
            criteria.add(Restrictions.in("type", types));
        }
        if (owner != null) {
            criteria.add(Restrictions.eq("owner", owner));
        }

        return listByCriteria(criteria, pageRequest);
    }

    /**
     * @param updatedConsumer updated Consumer values.
     * @return Updated consumers
     */
    @Transactional
    public Consumer update(Consumer updatedConsumer) {
        return update(updatedConsumer, true);
    }

    /**
     * Updates an existing consumer with the state specified by the given Consumer instance. If the
     * consumer has not yet been created, it will be created.
     * <p></p>
     * <strong>Warning:</strong> Using an pre-existing and persisted Consumer entity as the update
     * to apply may cause issues, as Hibernate may opt to save changes to nested collections
     * (facts, guestIds, tags, etc.) when any other database operation is performed. To avoid this
     * issue, it is advised to use only detached or otherwise unmanaged entities for the updated
     * consumer to pass to this method.
     *
     * @param updatedConsumer
     *  A Consumer instance representing the updated state of a consumer
     *
     * @param flush
     *  Whether or not to flush pending database operations after creating or updating the given
     *  consumer
     *
     * @return
     *  The persisted, updated consumer
     */
    @Transactional
    public Consumer update(Consumer updatedConsumer, boolean flush) {
        // TODO: FIXME:
        // We really need to use a DTO here. Hibernate has so many pitfalls with this approach that
        // can and will lead to odd, broken or out-of-order behavior.

        // Validate inbound facts before even attempting to apply the update
        this.validateFacts(updatedConsumer);

        Consumer existingConsumer = find(updatedConsumer.getId());

        if (existingConsumer == null) {
            return this.create(updatedConsumer);
        }

        // TODO: Are any of these read-only?
        existingConsumer.setEntitlements(entitlementCurator.bulkUpdate(updatedConsumer.getEntitlements()));

        // This set of updates is strange. We're ignoring the "null-as-no-change" semantics we use
        // everywhere else, and just blindly copying everything over.
        existingConsumer.setFacts(updatedConsumer.getFacts());
        existingConsumer.setName(updatedConsumer.getName());
        existingConsumer.setOwner(updatedConsumer.getOwner());
        existingConsumer.setType(updatedConsumer.getType());
        existingConsumer.setUuid(updatedConsumer.getUuid());

        if (flush) {
            save(existingConsumer);
        }

        return existingConsumer;
    }
    /**
     * Modifies the last check in and persists the entity. Make sure that the data
     * is refreshed before using this method.
     * @param consumer the consumer to update
     */
    public void updateLastCheckin(Consumer consumer) {
        this.updateLastCheckin(consumer, new Date());
    }

    @Transactional
    public void updateLastCheckin(Consumer consumer, Date checkinDate) {
        consumer.addCheckIn(checkinDate);
        save(consumer);
    }

    private boolean factsChanged(Map<String, String> updatedFacts,
        Map<String, String> existingFacts) {
        return !existingFacts.equals(updatedFacts);
    }

    /**
     * Validates the facts associated with the given consumer. If any fact fails validation a
     * PropertyValidationException will be thrown.
     *
     * @param consumer
     *  The consumer containing the facts to validate
     */
    private void validateFacts(Consumer consumer) {
        // Impl note:
        // Unlike the previous implementation, we are no longer attempting to "fix" anything here;
        // if it's broken at this point, we're in trouble, so we're going to throw an exception
        // instead of waiting for CP to die with a DB exception sometime in the very near future.
        //
        // Also, we're no longer using ConfigProperties.CONSUMER_FACTS_MATCHER at this point, as
        // it's something that belongs with the other input validation and filtering.

        Map<String, String> facts = consumer.getFacts();
        if (facts != null) {
            for (Entry<String, String> fact : facts.entrySet()) {
                this.factValidator.validate(fact.getKey(), fact.getValue());
            }
        }
    }

    /**
     * @param consumers consumers to update
     * @return updated consumers
     */
    @Transactional
    public Set<Consumer> bulkUpdate(Set<Consumer> consumers) {
        Set<Consumer> toReturn = new HashSet<Consumer>();
        for (Consumer toUpdate : consumers) {
            toReturn.add(update(toUpdate));
        }
        return toReturn;
    }

    /**
     * Get host consumer for a guest system id.
     *
     * As multiple hosts could have reported the same guest ID, we find the newest
     * and assume this is the authoritative host for the guest.
     *
     * This search needs to be case insensitive as some hypervisors report uppercase
     * guest UUIDs, when the guest itself will report lowercase.
     *
     * @param guestId a virtual guest ID (not a consumer UUID)
     * @return host consumer who most recently reported the given guestId (if any)
     */
    @Transactional
    public Consumer getHost(String guestId, Owner owner) {
        Disjunction guestIdCrit = Restrictions.disjunction();
        for (String possibleId : Util.getPossibleUuids(guestId)) {
            guestIdCrit.add(Restrictions.eq("guestId", possibleId).ignoreCase());
        }
        Criteria crit = currentSession()
            .createCriteria(GuestId.class)
            .createAlias("consumer", "gconsumer")
            .createAlias("gconsumer.guestIdsCheckIns", "checkins")
            .add(Restrictions.eq("gconsumer.owner", owner))
            .addOrder(Order.desc("checkins.updated"))
            .setMaxResults(1)
            .setProjection(Projections.property("consumer"));
        return (Consumer) crit.add(guestIdCrit).uniqueResult();
    }

    /**
     * Get the host consumer for the given virt guest IDs, if one exists.
     *
     * As multiple hosts could have reported the same guest ID, we find the newest
     * and assume this is the authoritative host for the guest.
     *
     * This search needs to be case insensitive as some hypervisors report uppercase
     * guest UUIDs, when the guest itself will report lowercase.
     *
     * @param guestIds
     * @return host consumers who most recently reported the given guestIds (if any)
     */
    @Transactional
    public VirtConsumerMap getGuestsHostMap(Owner owner, Set<String> guestIds) {
        List<Consumer> hypervisors = new ArrayList<Consumer>();
        int fromIndex = 0;
        int toIndex = fromIndex + MAX_IN_QUERY_LENGTH;

        Object[] ids = Util.getPossibleUuids(guestIds.toArray(new String [guestIds.size()])).toArray();

        while (fromIndex < ids.length) {
            if (toIndex >= ids.length) {
                toIndex = ids.length;
            }
            Criteria crit = currentSession()
                .createCriteria(GuestId.class)
                .createAlias("consumer", "gconsumer")
                .createAlias("gconsumer.guestIdsCheckIns", "checkins")
                .add(Restrictions.eq("gconsumer.owner", owner))
                .addOrder(Order.desc("checkins.updated"))
                .setProjection(Projections.property("consumer"));

            // Note: may contain duplicates but is sorted so they appear later:
            crit.add(new InExpressionIgnoringCase("guestId", Arrays.copyOfRange(ids, fromIndex, toIndex)));
            hypervisors.addAll(crit.list());
            fromIndex = toIndex;
            toIndex += MAX_IN_QUERY_LENGTH;
        }
        VirtConsumerMap result = new VirtConsumerMap();
        for (Consumer hypervisor : hypervisors) {
            for (GuestId gid : hypervisor.getGuestIds()) {
                result.add(gid.getGuestId(), hypervisor);
            }
        }
        return result;
    }

    /**
     * Get guest consumers for a host consumer.
     *
     * @param consumer host consumer to find the guests for
     * @return list of registered guest consumers for this host
     */
    @Transactional
    public List<Consumer> getGuests(Consumer consumer) {
        if (consumer.getFact("virt.uuid") != null &&
            !consumer.getFact("virt.uuid").trim().equals("")) {
            throw new BadRequestException(i18n.tr(
                "The system with UUID {0} is a virtual guest. " +
                "It does not have guests.",
                consumer.getUuid()));
        }
        List<Consumer> guests = new ArrayList<Consumer>();
        List<GuestId> consumerGuests = consumer.getGuestIds();
        if (consumerGuests != null) {
            for (GuestId cg : consumerGuests) {
                // Check if this is the most recent host to report the guest by asking
                // for the consumer's current host and comparing it to ourselves.
                if (consumer.equals(getHost(cg.getGuestId(), consumer.getOwner()))) {
                    Consumer guest = findByVirtUuid(cg.getGuestId(),
                        consumer.getOwner().getId());
                    if (guest != null) {
                        guests.add(guest);
                    }
                }
            }
        }
        return guests;
    }

    /**
     * This is an insecure query, because we need to know whether or not the
     * consumer exists
     *
     * We do not require that the hypervisor be consumerType hypervisor
     * because we need to allow regular consumers to be given
     * HypervisorIds to be updated via hypervisorResource
     *
     * @param hypervisorId Unique identifier of the hypervisor
     * @param owner Org namespace to search
     * @return Consumer that matches the given
     */
    @Transactional
    public Consumer getHypervisor(String hypervisorId, Owner owner) {
        return (Consumer) currentSession().createCriteria(Consumer.class)
            .add(Restrictions.eq("owner", owner))
            .createAlias("hypervisorId", "hvsr")
            .add(Restrictions.eq("hvsr.hypervisorId", hypervisorId).ignoreCase())
            .setMaxResults(1)
            .uniqueResult();
    }

    /**
     * Lookup all registered consumers matching one of the given hypervisor IDs.
     *
     * Results are returned as a map of hypervisor ID to the consumer record created.
     * If a hypervisor ID is not in the map, this indicates the hypervisor consumer does
     * not exist, i.e. it is new and needs to be created.
     *
     * This is an unsecured query, manually limited to an owner by the parameter given.
     * @param owner Owner to limit results to.
     * @param hypervisorIds List of hypervisor IDs as reported by the virt fabric.
     *
     * @return VirtConsumerMap of hypervisor ID to it's consumer, or null if none exists.
     */
    @Transactional
    public VirtConsumerMap getHostConsumersMap(Owner owner,
            Collection<String> hypervisorIds) {
        List<Consumer> results = getHypervisorsBulk(hypervisorIds, owner.getKey());
        VirtConsumerMap hypervisorMap = new VirtConsumerMap();
        for (Consumer c : results) {
            hypervisorMap.add(c.getHypervisorId().getHypervisorId(), c);
        }
        return hypervisorMap;
    }

    /**
     * @param hypervisorIds list of unique hypervisor identifiers
     * @param ownerKey Org namespace to search
     * @return Consumer that matches the given
     */
    @SuppressWarnings("unchecked")
    @Transactional
    public List<Consumer> getHypervisorsBulk(Collection<String> hypervisorIds,
            String ownerKey) {
        if (hypervisorIds == null || hypervisorIds.isEmpty()) {
            return new LinkedList<Consumer>();
        }
        LinkedList<String> hypervisorIdsUpper = new LinkedList<String>();
        // Check for hypervisor ids case-insensitively
        for (String hypervisorId : hypervisorIds) {
            hypervisorIdsUpper.add(hypervisorId.toUpperCase());
        }
        List<Consumer> consumers = new LinkedList<Consumer>();
        /** The query below is done as it is to support locking on our various supported databases.
         * When performing multiple simultaneous hypervisor checkins if both requests include at least two of the same
         * hypervisorIds (and they do not occur in the same order in their respective lists), a deadlock can occur.
         * By sorting the list of hypervisorIds as well as ordering the returned consumers by hypervisorId we can be
         * sure that, should the above situation occur, the two hypervisorIds that are shared between checkins will be
         * locked in the same order (meaning one transaction will wait for the lock first acquired by the other to be
         * released before proceeding)
         */
        String query = "SELECT c FROM Consumer c WHERE " +
                       "(c.owner.key = :ownerKey AND UPPER(c.hypervisorId.hypervisorId) IN (:hypervisorIds)) " +
                       "ORDER BY c.hypervisorId.hypervisorId ASC";
        javax.persistence.Query q = getEntityManager().createQuery(query);
        java.util.Collections.sort(hypervisorIdsUpper);
        int fromIndex = 0;
        int toIndex = fromIndex + MAX_IN_QUERY_LENGTH;
        // Do not exceed the MAX_IN_QUERY_LENGTH
        while (fromIndex < hypervisorIdsUpper.size()) {
            if (toIndex > hypervisorIdsUpper.size()) {
                toIndex = hypervisorIdsUpper.size();
            }
            List<String> subList = hypervisorIdsUpper.subList(fromIndex, toIndex);
            q.setLockMode(LockModeType.PESSIMISTIC_WRITE);
            q.setParameter("ownerKey", ownerKey);
            q.setParameter("hypervisorIds", subList);
            consumers.addAll(q.getResultList());
            fromIndex = toIndex;
            toIndex += MAX_IN_QUERY_LENGTH;
        }
        return consumers;
    }

    @SuppressWarnings("unchecked")
    @Transactional
    public List<Consumer> getHypervisorsForOwner(String ownerKey) {
        return createSecureCriteria()
            .createAlias("owner", "o")
            .add(Restrictions.eq("o.key", ownerKey))
            .createAlias("hypervisorId", "hvsr")
            .add(Restrictions.isNotNull("hvsr.hypervisorId"))
            .list();
    }

    public boolean doesConsumerExist(String uuid) {
        long result = (Long) createSecureCriteria()
            .add(Restrictions.eq("uuid", uuid))
            .setProjection(Projections.count("id"))
            .uniqueResult();
        return result != 0;
    }

    public Consumer verifyAndLookupConsumer(String consumerUuid) {
        Consumer consumer = this.findByUuid(consumerUuid);

        if (consumer == null) {
            throw new NotFoundException(i18n.tr(
                "Unit with ID ''{0}'' could not be found.", consumerUuid));
        }
        return consumer;
    }

    public Page<List<Consumer>> searchOwnerConsumers(Owner owner, String userName,
            Collection<ConsumerType> types, List<String> uuids, List<String> hypervisorIds,
            List<KeyValueParameter> factFilters, PageRequest pageRequest) {
        Criteria crit = super.createSecureCriteria();
        if (owner != null) {
            crit.add(Restrictions.eq("owner", owner));
        }
        if (userName != null && !userName.isEmpty()) {
            crit.add(Restrictions.eq("username", userName));
        }
        if (types != null && !types.isEmpty()) {
            crit.add(Restrictions.in("type", types));
        }
        if (uuids != null && !uuids.isEmpty()) {
            crit.add(Restrictions.in("uuid", uuids));
        }
        if (hypervisorIds != null && !hypervisorIds.isEmpty()) {
            // Cannot use Restrictions.in here because hypervisorId is case insensitive
            Set<Criterion> ors = new HashSet<Criterion>();
            for (String hypervisorId : hypervisorIds) {
                ors.add(Restrictions.eq("hvsr.hypervisorId", hypervisorId).ignoreCase());
            }
            crit.createAlias("hypervisorId", "hvsr");
            crit.add(Restrictions.or(ors.toArray(new Criterion[ors.size()])));
        }
        if (factFilters != null && !factFilters.isEmpty()) {
            // Process the filters passed for the attributes
            FilterBuilder factFilter = new FactFilterBuilder();
            for (KeyValueParameter filterParam : factFilters) {
                factFilter.addAttributeFilter(filterParam.key(), filterParam.value());
            }
            factFilter.applyTo(crit);
        }
        return this.listByCriteria(crit, pageRequest);
    }

    /**
     * Finds the consumer count for an Owner based on type.
     *
     * @param owner the owner to count consumers for
     * @param type the type of the Consumer to filter on.
     * @return the number of consumers based on the type.
     */
    public int getConsumerCount(Owner owner, ConsumerType type) {
        Criteria c = createSecureCriteria()
            .add(Restrictions.eq("owner", owner))
            .add(Restrictions.eq("type", type));
        c.setProjection(Projections.rowCount());
        return ((Long) c.uniqueResult()).intValue();
    }

    public int getConsumerEntitlementCount(Owner owner, ConsumerType type) {
        Criteria c = createSecureCriteria()
            .add(Restrictions.eq("owner", owner))
            .add(Restrictions.eq("type", type))
            .createAlias("entitlements", "ent")
            .setMaxResults(0)
            .setProjection(Projections.sum("ent.quantity"));
        Long result = (Long) c.uniqueResult();
        return result == null ? 0 : result.intValue();
    }

    @SuppressWarnings("unchecked")
    public List<String> getConsumerIdsWithStartedEnts() {
        Date now = new Date();
        return currentSession().createCriteria(Entitlement.class)
            .createAlias("pool", "p")
            .add(Restrictions.eq("updatedOnStart", false))
            .add(Restrictions.lt("p.startDate", now))
            .setProjection(Projections.property("consumer.id"))
            .setResultTransformer(Criteria.DISTINCT_ROOT_ENTITY)
            .list();
    }
    /**
     * InExpressionIgnoringCase
     *
     * Allows Criterion for case insensitive comparison to list of values
     *
     * @author wpoteat
     */
    public class InExpressionIgnoringCase implements Criterion {

        private final String propertyName;
        private final Object[] values;

        public InExpressionIgnoringCase(final String propertyName, final Object[] values) {
            this.propertyName = propertyName;
            this.values = values;
        }

        public String toSqlString(final Criteria criteria, final CriteriaQuery criteriaQuery)
            throws HibernateException {
            final String[] columns = criteriaQuery.findColumns(this.propertyName, criteria);
            final String[] wrappedLowerColumns = wrapLower(columns);
            if (criteriaQuery.getFactory().getDialect().supportsRowValueConstructorSyntaxInInList() ||
                    columns.length <= 1) {

                String singleValueParam = StringHelper.repeat("lower(?), ", columns.length - 1) + "lower(?)";
                if (columns.length > 1) {
                    singleValueParam = '(' + singleValueParam + ')';
                }
                final String params = this.values.length > 0 ? StringHelper.repeat(singleValueParam + ", ",
                        this.values.length - 1) + singleValueParam : "";
                String cols = StringHelper.join(", ", wrappedLowerColumns);
                if (columns.length > 1) {
                    cols = '(' + cols + ')';
                }
                return cols + " in (" + params + ')';
            }
            else {
                String cols = " ( " + StringHelper.join(" = lower(?) and ",
                        wrappedLowerColumns) + "= lower(?) ) ";
                cols = this.values.length > 0 ? StringHelper.repeat(cols + "or ",
                        this.values.length - 1) + cols : "";
                cols = " ( " + cols + " ) ";
                return cols;
            }
        }

        public TypedValue[] getTypedValues(final Criteria criteria, final CriteriaQuery criteriaQuery)
            throws HibernateException {
            final ArrayList<TypedValue> list = new ArrayList<TypedValue>();
            final Type type = criteriaQuery.getTypeUsingProjection(criteria, this.propertyName);
            if (type.isComponentType()) {
                final CompositeType actype = (CompositeType) type;
                final Type[] types = actype.getSubtypes();
                for (int j = 0; j < this.values.length; j++) {
                    for (int i = 0; i < types.length; i++) {
                        final Object subval = this.values[j] == null ? null :
                            actype.getPropertyValues(this.values[j], EntityMode.POJO)[i];
                        list.add(new TypedValue(types[i], subval, EntityMode.POJO));
                    }
                }
            }
            else {
                for (int j = 0; j < this.values.length; j++) {
                    list.add(new TypedValue(type, this.values[j], EntityMode.POJO));
                }
            }
            return list.toArray(new TypedValue[list.size()]);
        }

        @Override
        public String toString() {
            return this.propertyName + " in (" + StringHelper.toString(this.values) + ')';
        }

        private String[] wrapLower(final String[] columns) {
            final String[] wrappedColumns = new String[columns.length];
            for (int i = 0; i < columns.length; i++) {
                wrappedColumns[i] = "lower(" + columns[i] + ")";
            }
            return wrappedColumns;
        }
    }
}
