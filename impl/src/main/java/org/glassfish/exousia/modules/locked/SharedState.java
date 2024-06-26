/*
 * Copyright (c) 2024 Contributors to the Eclipse Foundation.
 * Copyright (c) 1997, 2018 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.exousia.modules.locked;

import jakarta.security.jacc.PolicyContext;
import jakarta.security.jacc.PolicyContextException;

import java.lang.System.Logger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.WARNING;

/**
 * @author monzillo
 */
public class SharedState {

    private static final Logger LOG = System.getLogger(SharedState.class.getName());

    // lock on the shared configTable and linkTable
    private static ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock(true);
    private static Lock rLock = rwLock.readLock();
    private static Lock wLock = rwLock.writeLock();
    private static Map<String, SimplePolicyConfiguration> configTable = new HashMap<>();
    private static Map<String, Set<String>> linkTable = new HashMap<>();


    static SimplePolicyConfiguration lookupConfig(String pcid) {
        wLock.lock();
        try {
            return configTable.get(pcid);
        } finally {
            wLock.unlock();
        }
    }

    static SimplePolicyConfiguration getConfig(String pcid, boolean remove) {
        SimplePolicyConfiguration simplePolicyConfiguration = null;
        wLock.lock();
        try {
            simplePolicyConfiguration = configTable.get(pcid);
            if (simplePolicyConfiguration == null) {
                simplePolicyConfiguration = new SimplePolicyConfiguration(pcid);
                SharedState.initLinks(pcid);
                configTable.put(pcid, simplePolicyConfiguration);
            } else if (remove) {
                SharedState.removeLinks(pcid);
            }
        } finally {
            wLock.unlock();
        }

        return simplePolicyConfiguration;
    }

    static SimplePolicyConfiguration getActiveConfig() throws PolicyContextException {
        String contextId = PolicyContext.getContextID();
        SimplePolicyConfiguration simplePolicyConfiguration = null;
        if (contextId != null) {
            rLock.lock();
            try {
                simplePolicyConfiguration = configTable.get(contextId);
                if (simplePolicyConfiguration == null) {
                    // Unknown policy context set on thread return null to allow checking to be
                    // performed with default context.
                    // Should repair improper setting of context by encompassing runtime.
                    LOG.log(WARNING, "Invalid policy context id: {0}", contextId);
                }

            } finally {
                rLock.unlock();
            }
            if (simplePolicyConfiguration != null) {
                if (!simplePolicyConfiguration.inService()) {
                    // Policy context set on thread is not in service return null to allow checking
                    // to be performed with default context.
                    // Should repair improper setting of context by encompassing runtime.
                    LOG.log(DEBUG, "Invalid policy context state.");
                    simplePolicyConfiguration = null;
                }
            }
        }

        return simplePolicyConfiguration;
    }

    /**
     * Creates a relationship between this configuration and another such that they share the same principal-to-role
     * mappings. PolicyConfigurations are linked to apply a common principal-to-role mapping to multiple seperately
     * manageable PolicyConfigurations, as is required when an application is composed of multiple modules.
     * <P>
     * Note that the policy statements which comprise a role, or comprise the excluded or unchecked policy collections in a
     * PolicyConfiguration are unaffected by the configuration being linked to another.
     * <P>
     * The relationship formed by this method is symetric, transitive and idempotent.
     *
     * @param id
     * @param otherId
     * @throws jakarta.security.jacc.PolicyContextException If otherID equals receiverID. no relationship is formed.
     */
    static void link(String id, String otherId) throws jakarta.security.jacc.PolicyContextException {
        wLock.lock();
        try {
            if (otherId.equals(id)) {
                throw new IllegalArgumentException("Operation attempted to link PolicyConfiguration to itself.");
            }

            // Get the linkSet corresponding to this context
            Set<String> linkSet = linkTable.get(id);

            // Get the linkSet corresponding to the context being linked to this
            Set<String> otherLinkSet = linkTable.get(otherId);

            if (otherLinkSet == null) {
                throw new RuntimeException("Linked policy configuration (" + otherId + ") does not exist");
            }

            for (String nextid : otherLinkSet) {
                // Add the id to this linkSet
                linkSet.add(nextid);

                // Replace the linkset mapped to all the contexts being linked
                // to this context, with this linkset.
                linkTable.put(nextid, linkSet);
            }
        } finally {
            wLock.unlock();
        }
    }

    static void initLinks(String contextId) {
        // Create a new linkSet with only this context id, and put in the table.
        Set<String> linkSet = new HashSet<>();
        linkSet.add(contextId);
        linkTable.put(contextId, linkSet);
    }

    static void removeLinks(String contextId) {
        wLock.lock();
        try { // get the linkSet corresponding to this context.
            Set<String> linkSet = linkTable.get(contextId);

            // Remove this context id from the linkSet (which may be shared
            // with other contexts), and unmap the linkSet from this context.
            if (linkSet != null) {
                linkSet.remove(contextId);
                linkTable.remove(contextId);
            }

            initLinks(contextId);
        } finally {
            wLock.unlock();
        }
    }

}
