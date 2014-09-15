package com.spotify.heroic.http.rpc3;

import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.MediaType;

import com.spotify.heroic.async.Callback;
import com.spotify.heroic.http.HttpAsyncUtils;
import com.spotify.heroic.http.rpc.RpcWriteResult;
import com.spotify.heroic.metrics.BackendGroup;
import com.spotify.heroic.metrics.MetricBackendManager;
import com.spotify.heroic.metrics.model.MetricGroups;
import com.spotify.heroic.metrics.model.Statistics;
import com.spotify.heroic.metrics.model.WriteBatchResult;

@Path("/rpc3")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class Rpc3Resource {
    @Inject
    private MetricBackendManager metrics;

    private static final HttpAsyncUtils.Resume<MetricGroups, Rpc3MetricGroups> QUERY = new HttpAsyncUtils.Resume<MetricGroups, Rpc3MetricGroups>() {
        @Override
        public Rpc3MetricGroups resume(MetricGroups value) throws Exception {
            return new Rpc3MetricGroups(value.getGroups(),
                    convert(value.getStatistics()));
        }

        private Rpc3Statistics convert(Statistics s) {
            return new Rpc3Statistics(s.getAggregator(), s.getRow(),
                    s.getCache(), Rpc3Statistics.Rpc.EMPTY);
        }
    };

    private static final HttpAsyncUtils.Resume<WriteBatchResult, RpcWriteResult> WRITE = new HttpAsyncUtils.Resume<WriteBatchResult, RpcWriteResult>() {
        @Override
        public RpcWriteResult resume(WriteBatchResult value) throws Exception {
            return new RpcWriteResult(value.isOk());
        }
    };

    @POST
    @Path("/query")
    public void query(@Suspended final AsyncResponse response,
            Rpc3QueryBody query) throws Exception {
        final Callback<MetricGroups> callback = metrics.useGroup(
                query.getBackendGroup()).groupedQuery(query.getGroup(),
                query.getFilter(), query.getSeries(), query.getRange(),
                        query.getAggregationGroup());

        HttpAsyncUtils.handleAsyncResume(response, callback, QUERY);
    }

    @POST
    @Path("/write")
    public void write(@Suspended final AsyncResponse response,
            Rpc3WriteBody body) throws Exception {
        final BackendGroup backend = metrics.useGroup(body.getBackendGroup());

        final Callback<WriteBatchResult> callback = metrics.write(backend,
                body.getWrites());

        HttpAsyncUtils.handleAsyncResume(response, callback, WRITE);
    }

    @POST
    @Path("/full-query")
    public void query(@Suspended final AsyncResponse response,
            Rpc3FullQueryBody body) throws Exception {
        final Callback<MetricGroups> callback = metrics.directQueryMetrics(
                body.getBackendGroup(), body.getFilter(), body.getGroupBy(),
                body.getRange(), body.getAggregation());
        HttpAsyncUtils.handleAsyncResume(response, callback, QUERY);
    }
}