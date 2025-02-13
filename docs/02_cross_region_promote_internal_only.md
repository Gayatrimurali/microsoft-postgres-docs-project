# Introduction

A "site-swap" represents a user-initiated simulation of a failure, typically at the regional level. While the main use case for this feature involves handling regional failures or relocating the primary server to a different region, it's important to note that it can also be used within the same region.

## Concepts

The purpose of the site-swap is to test an application's readiness and compliance or to move the primary server back to the original region after an outage has been resolved (failback). The process involves a swap of node roles within the cluster, transforming the read replica into the primary while the old primary re-joins the replication cluster as a replica of the new primary along with other replicas if present. Site-swap supports both in-region and cross-region failover.

The implementation favors reusing the old primary over creating a new replica due to the speed of the process when compared to the alternative method of promoting a new primary and recreating the replica, and to avoid potential capacity issues in some regions.

The procedures for a site-swap are symmetric, mseaning they work identically when moving to the failover region or moving back, barring any specific features like geo-redundant backup that may affect the process. However, from a business perspective, site-swaps may not be entirely symmetrical. Typically, customers may have a primary region where they operate more frequently, and a different region reserved for Disaster Recovery (DR) purposes.  

> NOTE: Due to the asynchronous setup of replicas, data loss might occur. It's currently the user's responsibility to halt applications and other mechanisms like [autovacuum](https://www.postgresql.org/docs/current/routine-vacuuming.html) on the primary to prevent changes from being applied. This temporary halt allows for replication to catch up with changes until promotion can be safely performed without data loss.  You can [monitor the primary's metrics](https://learn.microsoft.com/en-us/azure/postgresql/flexible-server/concepts-monitoring) to watch for connections and activity to complete before making the site-swap.

> NOTE: During a site-swap operation, the service automatically switches the roles of the servers. Currently, it is not supported to "pause" existing connections during a swap.  Active connections may be forcibly closed at the time of the swap.

## Site-swap versus Geo-replication

When performing a zonal failover with [single and multi-zone-redundant high availablity](https://learn.microsoft.com/en-us/azure/postgresql/flexible-server/concepts-high-availability), failover act very similar to site-swap.  Both execute failovers with very small RPO and RTO and the service endpoint will not change. Applications will continue to work normally.

When performing failover between two different regions without site-swap, there are several more steps that must be performed to ensure a successful failover.  This can be as simple as manually changing connection strings, or having a [public/private DNS](https://techcommunity.microsoft.com/t5/azure-database-for-postgresql/failover-between-regions-with-azure-postgresql-flexible-server/ba-p/3768115) in place to manage the application traffic during the cross-region failover.

For many architectures, it may not be acceptable to change connection strings or to setup DNS to support this scenerio.  Site-swap is designed to address these particular use-cases and provide a seamless, integrated process for failing over to different regions with Azure Database for PostgreSQL.

## Requirements

### General

In order to use site-swap, your Azure Database for PostgreSQL must meet some basic requirements:

- Primary server can only have one Replica Server.
- Both Primary and Replica servers should be in succeeded state.

### Networking

Site-swap supports both Public Network connected or virtual network injected instances. However, for virtual network  servers across Azure regions, [network security group (NSG) rules](https://learn.microsoft.com/en-us/azure/postgresql/flexible-server/concepts-networking) must configured to allow traffic between the Primary and Replica servers.

## Virutal endpoints

To avoid the situations mentioned above, logical entities called Virtual Endpoints have been introduced.  These virtual endpoints layer over the primary server and its read replicas. Virtual endpoints simplify user interactions with the underlying servers, ensuring efficient routing and operation distribution.

Composed of two distinct entities - the `Writer` and the `Reader` endpoints - each with its own unique Fully Qualified Domain Name (FQDN).  Within these two entities there are three sub-types of virtual endpoints: `ReaderWriter`, `Custom`, and `CustomWriter` 

The ReaderWriter endpoint maintains consistent connection strings independently of any swap opeartions. It is this feature that spares users from modifying their application connection strings after a failover.

- **Writer Endpoint (R/W)**: Tasked with handling read and write operations, this endpoint automatically routes to the current primary server, ensuring user connection strings remain consistent, even after a swap.

- **Reader Endpoint (RO)**: Tailored for read-only operations. It points to a user-selected replica server and designates the target server during a swap operation.

- **Custom Endpoint**: This non-functional endpoint remains tied to a specific instance, maintaining its pointer regardless of any swaps.

- **CustomWriter Endpoint**: This functional endpoint adjusts according to the primary role in the cluster, altering its direction during swaps.

### Endpoint Management

This table below provides the flexibility of each type of endpoint in terms of independent creation, updating, and deletion, and how these operations are related or dependent on one another.

| Endpoint Type              | Create Alone | Update | Delete | Note
| :---------------- | :------ | :---- | :---- | :---- |
| Writer Endpoint            |   Yes   | Not supported | No, deleted Reader endpoint | Functional endpoint and always depends on the instance role in the cluster. If the primary is down, the writer endpoint will return an error and users need to trigger a swap.
| Reader Endpoint            |   Yes   | Not supported | No, deleted Reader endpoint | Functional endpoint and always depends on the instance role in the cluster. If the primary is down, the writer endpoint will return an error and users need to trigger a swap.
| Custom Endpoint            |   Yes   | Yes | Yes | -
| CustomWrite Endpoint            |   Yes   | Not supported | Yes | Functional endpoint dependent on instance role in cluster.

### Endpoint Operations

The following table provides an overview of the operations on virtual endpoints based on specific use cases. The use cases include: 'Region Up', which signifies that all instances are healthy, 'Primary Region Down', indicating that the entire region or just the primary server is down, and 'Replica Region Down', where the region or replica instance is down.

| Endpoint              | Operation | Region Up | Primary Down | Replica Region Down
| :---------------- | :------ | :---- | :---- | :---- |
| Writer            |   Create, Delete, Get   | Supported | Not supported | Supported
| Writer            |   Update   | N/A | N/A | N/A
| Reader            |   Create, Update   | Supported | Not supported | Supported, except in instance in replica region
| Reader            |   Delete, Get   | Supported | Not supported | Supported
| Custom            |   Create, Update   | Supported | Not supported | Supported, except in instance in replica region
| Custom            |   Delete, Get   | Supported | Not supported | Supported
| CustomWriter      |   Create, Delete, Get   | Supported | Not supported | Supported
| CustomWriter      |   Update   | N/A | N/A | N/A

## Site-swap operational behavior

When performing a successful site-swap operation, the states of the primary and replica instances are vital to the process.  The following sections describe the process for performing a failover with varying instance states.

### Primary and replica are healthy, all regions healthy

1. Fence the primary server (transactional).
2. Disable HA if it is enabled (not transactional).
3. Promote replica (transactional). Attempt a rewind if this process fails.
4. Move the writer endpoint (transactional).
5. Change the old primary to a replica (transactional). Attempt a rewind if this process fails. It will have a status of down/inactive while rewinding, but if it fails, we will drop and recreate it.
6. Move the reader endpoint (transactional).

### Primary down

1. Promote replica (transactional). No rewind attempt since the primary is down. If this process fails, the entire operation ends with failure.
2. Move the writer endpoint (transactional). Reader endpoint returns error, it’s changed to point to old primary now, we prioritize primary over replica workload.

    > NOTE: While primary remains down that’s the end of the process. Once it comes back up the following actions are performed.

3. Change the old primary to a replica (transactional). It will have a status of down/inactive while rewinding. In case of failure drop and recreation will be done.
4. Disable HA if it is enabled (standby removed). (non-transactional)

### Replica down
s
In the event the replica instance is down or the entire replica region is down a swap is not possible. Users can change the reader endpoint to point to another, healthy replica, and trigger swap.

## Cross-feature behavior

When creating a replica, it is possible that the settings of the replica are not identical to the primary.  In some cases these difference may not make site-swap possible.

| Feature              | Before | After | Supported | Details
| :---------------- | :------ | :---- | :---- | :---- |
| SKU            |   Same   | Same | Yes | None
| SKU            |   Higher   | N/A | No | None
| SKU            |   Lower   | N/A | No | None
| Server Params  |   Same   | Same | Yes | None
| Server Params  |   Higher   | Same | Yes | None
| Server Params  |   Lower   | N/A | No | Lower values will block swap
| HA            |   Primary HA   | Primary w/o HA | Yes | Can be enabled after swap.
| HA            |   Replica w/o HA   | New replicat w/o HA | Yes | None
| Power         |   Replica Active   | N/A | Yes | None
| Power            |   Primary Active   | N/A | Yes | None
| Power            |   Replica stopped   | N/A | No | None
| Power            |   Primary stopped   | N/A | No | None

> NOTE: The failover process will migrated a small set of configuration parameter values to new primary. these include `max_connections`, `max_worker_processes`, `max_wal_senders`, `max_prepared_transactions`, `max_locks_per_transaction`, The remaining server parameters must be manually migrated.

GRS (geo-backup and restore) is the only feature that’s behavior of the initial swap is not symmetric to behavior of swap back and is described below.

| Before | After | Supported
| :------ | :---- | :---- |
|   Primary has GRS   | New primary w/o GRS | Yes
|   Primary has GRS   | N/A | No
|   Replica w/o GRS   | New replica w/o GRS | Yes
|   Primary w/o GRS   | Primary has GRS | Yes

> NOTE: Other features, including AAD, CMK, Firewall rules, pgBouncer, and PiTR, are tied to the instance and will not be affected by a swap or swap back. It's worth noting that in the case of PiTR specifically, it can only go back as far as the point of the site swap.

> NOTE: A site-swap operation disables High Availability on Primary server if it was enabled. Post failover, It must be explicitly re-enabled on the Primary.

## Use Cases

The following are two use cases for enabling and using Azure Database for PostgreSQL site-swap in your architecture(s).

### HA enabled cluster with one DR replica

As a Data Officer in a well-established financial institution, I need to make sure that data in our Tier-1 systems are well protected in case of any outage or regional disasters. I’m familiar with Flexible Server zone redundant HA feature that provides zone resiliency, and this already has been listed as a must-have requirement for Tier-1 applications. I also need to make sure that in case of region-level failures my data is protected, and the system will be operational independently of the primary region recovery time. Ideally, I would like to have my data replicated synchronously to another region, but I’m also aware of the overhead it might cause so I can accept minimal data loss in case of regional failure, my RPO is up to 5 minutes. I used logical replication to another region once read replica feature wasn’t available, but I need to be able to replicate DDL statements as well as I don’t want to recreate the replicas each time failover is triggered as this imposes risk to my data in case regional failure would occur during the creation. I also don’t want to add additional work to our engineering team as logical replication is not part of the PaaS service and would need to be managed by them.

- When HA failover is triggered, you want to be sure that the replica is operational, no data is lost, and the system keeps the ability to failover to a different region in case a subsequent regional failure were to occur. In addition, you may be looking for:

- When a site-swap is completed the deployed Postgres cluster should have the same zone redundant resiliency after the switch than it had before. For example, if primary has zone redundancy in region 2, then it should support it in the  subsequent step in my automation.

- Should be possible to switch the region back to its day 1 operations pattern (aks failback).

- Entire operation must run in less than 30 minutes, ideally 10 minutes, to meet required RTO.

- Not lose more than 5 minutes of data (RPO) in case of planned swap.

- Switch should be transparent to consuming applications – no connection strings update in applications required.

### Site-swap as part of business continuity certification

Data Architect at a large organziation wants to standardize on Azure Database for PostgreSQL Flexible server but as part of the certification process, requires that the system fulfills the standard disaster recovery requirements for Tier-1 systems.

These Tier-1 systems are required to undergo quarterly DR testing, moving workloads from one region to another then vice versa. So, a solution where fail-over is in line with their RTO/RPO expectations but fail-back takes a lot of time (like rebuilding a read replica from scratch) is something she wants to avoid.  Switching should be transparent to consuming applications – no connection strings update in applications required.  

- The old primary is reused and its role changes to speed up the process and avoid capacity problems.

- It should also be possible to switch the region back to its day 1 operations pattern (failback).

- Operation is symmetric when it comes to initial switchover as well as failback. With exception to GRS.

- No mechanism of cancelation already started.

- The entire operation must run in less than 30 minutes, ideally 10 minutes, to meet required RTO.

- Must not lose any data (RPO=0), replica is synchronized with the primary before it is switched (for now it’s customer responsibility).

## Next Steps

Explore the how-to guides to enable and test cross region site swap.

- [Enable Cross-Region Site Swap for existing Azure Database for PostgreSQL - Flexible Server](TBD)
- [Deploy and test new cross-region site swap Azure Database for PostgreSQL - Flexible Server](TBD)
