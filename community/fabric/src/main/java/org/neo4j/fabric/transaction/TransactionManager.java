/*
 * Copyright (c) 2002-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.fabric.transaction;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.neo4j.fabric.bookmark.TransactionBookmarkManager;
import org.neo4j.fabric.config.FabricConfig;
import org.neo4j.fabric.executor.FabricLocalExecutor;
import org.neo4j.fabric.executor.FabricRemoteExecutor;
import org.neo4j.internal.kernel.api.security.LoginContext;
import org.neo4j.kernel.impl.coreapi.InternalTransaction;
import org.neo4j.kernel.api.exceptions.Status;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;
import org.neo4j.logging.internal.LogService;
import org.neo4j.scheduler.JobScheduler;

public class TransactionManager extends LifecycleAdapter
{

    private final FabricRemoteExecutor remoteExecutor;
    private final FabricLocalExecutor localExecutor;
    private final LogService logService;
    private final JobScheduler jobScheduler;
    private final FabricConfig fabricConfig;

    private final Set<FabricTransactionImpl> openTransactions = ConcurrentHashMap.newKeySet();

    public TransactionManager( FabricRemoteExecutor remoteExecutor,
            FabricLocalExecutor localExecutor,
            LogService logService,
            JobScheduler jobScheduler,
            FabricConfig fabricConfig )
    {
        this.remoteExecutor = remoteExecutor;
        this.localExecutor = localExecutor;
        this.logService = logService;
        this.jobScheduler = jobScheduler;
        this.fabricConfig = fabricConfig;
    }

    public FabricTransaction begin( FabricTransactionInfo transactionInfo, TransactionBookmarkManager transactionBookmarkManager )
    {
        transactionInfo.getLoginContext().authorize( LoginContext.IdLookup.EMPTY, transactionInfo.getDatabaseName() );

        FabricTransactionImpl fabricTransaction = new FabricTransactionImpl( transactionInfo,
                transactionBookmarkManager,
                remoteExecutor,
                localExecutor,
                logService,
                this,
                jobScheduler,
                fabricConfig );
        openTransactions.add( fabricTransaction );
        return fabricTransaction;
    }

    @Override
    public void stop()
    {
        openTransactions.forEach( tx -> tx.markForTermination( Status.Transaction.Terminated ) );
    }

    void removeTransaction( FabricTransactionImpl transaction )
    {
        openTransactions.remove( transaction );
    }

    public Set<FabricTransaction> getOpenTransactions()
    {
        return Collections.unmodifiableSet( openTransactions );
    }

    public Optional<FabricTransaction> findTransactionContaining( InternalTransaction transaction )
    {
        return openTransactions.stream()
                               .filter( tx -> tx.getInternalTransactions().stream()
                                                .anyMatch( itx -> itx.kernelTransaction() == transaction.kernelTransaction() ) )
                               .map( FabricTransaction.class::cast )
                               .findFirst();
    }
}
