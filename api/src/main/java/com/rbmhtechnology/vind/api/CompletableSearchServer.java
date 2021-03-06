package com.rbmhtechnology.vind.api;

import com.rbmhtechnology.vind.annotations.AnnotationUtil;
import com.rbmhtechnology.vind.api.query.FulltextSearch;
import com.rbmhtechnology.vind.api.query.delete.Delete;
import com.rbmhtechnology.vind.api.query.get.RealTimeGet;
import com.rbmhtechnology.vind.api.query.suggestion.ExecutableSuggestionSearch;
import com.rbmhtechnology.vind.api.query.update.Update;
import com.rbmhtechnology.vind.api.result.BeanSearchResult;
import com.rbmhtechnology.vind.api.result.GetResult;
import com.rbmhtechnology.vind.api.result.SearchResult;
import com.rbmhtechnology.vind.api.result.SuggestionResult;
import com.rbmhtechnology.vind.configure.SearchConfiguration;
import com.rbmhtechnology.vind.model.DocumentFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 *
 */
public class CompletableSearchServer extends SearchServer {

    private final SearchServer backend;
    private final Executor executor;
    private final boolean shutdownExecutorOnClose;

    public CompletableSearchServer(SearchServer backend, Executor executor) {
        this(backend, executor, false);
    }

    private CompletableSearchServer(SearchServer backend, Executor executor, boolean shutdownExecutorOnClose) {
        if(shutdownExecutorOnClose && !(executor instanceof ExecutorService)) {
            throw new IllegalArgumentException("shutdownExecutorOnClose requires 'executor' being an 'ExecutorService', actually got: " + executor.getClass());
        }
        this.backend = backend;
        this.executor = executor;
        this.shutdownExecutorOnClose = shutdownExecutorOnClose;
    }

    public CompletableSearchServer(SearchServer backend) {
        this(backend, Executors.newFixedThreadPool(SearchConfiguration.get(SearchConfiguration.APPLICATION_EXECUTOR_THREADS,16)), true);
    }

    public <T> CompletableFuture<BeanSearchResult<T>> executeAsync(FulltextSearch search, Class<T> c) {
        return executeAsync(search, c, executor);
    }

    public <T> CompletableFuture<BeanSearchResult<T>> executeAsync(FulltextSearch search, Class<T> c, Executor executor) {
        return CompletableFuture.supplyAsync(() -> this.execute(search, c), executor);
    }

    @Deprecated
    public final CompletableFuture<Void> indexAsync(Object t) {
        return indexBeanAsync(t);
    }
    @Deprecated
    public CompletableFuture<Void> indexBeanAsync(Object t) {
        return indexBeanAsync(t, executor);
    }

    public CompletableFuture<Void> indexBeanAsync(Object ... t) {
        return indexBeanAsync(executor, t);
    }

    public CompletableFuture<Void> indexBeanAsync(List<Object> t) {
        return indexBeanAsync(executor, t);
    }

    @Deprecated
    public final CompletableFuture<Void> indexAsync(Object t, Executor executor) {
        return indexBeanAsync(t, executor);
    }
    @Deprecated
    public CompletableFuture<Void> indexBeanAsync(Object t, Executor executor) {
        return indexAsync(executor,AnnotationUtil.createDocument(t));
    }

    public CompletableFuture<Void> indexBeanAsync(Executor executor, Object ... t) {
        List<Document> beanDocuments = new ArrayList<>();

        for (Object bean : t){
            beanDocuments.add(AnnotationUtil.createDocument(bean));
        }
        return indexAsync(executor,beanDocuments);
    }

    public CompletableFuture<Void> indexBeanAsync( Executor executor, List<Object> t) {
        List<Document> beanDocuments = new ArrayList<>();

        for (Object bean : t){
            beanDocuments.add(AnnotationUtil.createDocument(bean));
        }
        return indexAsync(executor,beanDocuments);
    }

    @Deprecated
    public CompletableFuture<Void> indexAsync(Document doc) {
        return indexAsync(doc, executor);
    }

    @Deprecated
    public CompletableFuture<Void> indexAsync(Document doc, Executor executor) {
        return CompletableFuture.runAsync(() -> this.index(doc), executor);
    }

    public CompletableFuture<Void> indexAsync(Document ... docs) {
        return indexAsync(executor, docs);
    }

    public CompletableFuture<Void> indexAsync(Executor executor, Document ... docs) {
        return CompletableFuture.runAsync(() -> this.index(docs), executor);
    }

    public CompletableFuture<Void> indexAsync(List<Document> docs) {
        return indexAsync(executor, docs);
    }

    public CompletableFuture<Void> indexAsync(Executor executor,List<Document> docs) {
        return CompletableFuture.runAsync(() -> this.index(docs), executor);
    }

    @Deprecated
    public final CompletableFuture<Void> deleteAsync(Object t) {
        return deleteBeanAsync(t);
    }

    public CompletableFuture<Void> deleteBeanAsync(Object t) {
        return deleteBeanAsync(t, executor);
    }

    @Deprecated
    public final CompletableFuture<Void> deleteAsync(Object t, Executor executor) {
        return deleteBeanAsync(t, executor);
    }

    public CompletableFuture<Void> deleteBeanAsync(Object t, Executor executor) {
        return deleteAsync(AnnotationUtil.createDocument(t), executor);
    }

    public CompletableFuture<Void> deleteAsync(Document doc) {
        return deleteAsync(doc, executor);
    }

    public CompletableFuture<Void> deleteAsync(Document doc, Executor executor) {
        return CompletableFuture.runAsync(() -> this.delete(doc), executor);
    }

    public CompletableFuture<Void> executeAsync(Update update, DocumentFactory factory) {
        return executeAsync(update, factory, executor);
    }

    public CompletableFuture<Void> executeAsync(Update update, DocumentFactory factory, Executor executor) {
        return CompletableFuture.runAsync(() -> this.execute(update, factory), executor);
    }

    public CompletableFuture<Void> commitAsync(boolean optimize) {
        return commitAsync(optimize, executor);
    }

    public CompletableFuture<Void> commitAsync(boolean optimize, Executor executor) {
        return CompletableFuture.runAsync(() -> commit(optimize), executor);
    }

    public CompletableFuture<Void> commitAsync() {
        return commitAsync(executor);
    }

    public CompletableFuture<Void> commitAsync(Executor executor) {
        return CompletableFuture.runAsync(this::commit, executor);
    }

    public CompletableFuture<SearchResult> executeAsync(FulltextSearch search, DocumentFactory factory) {
        return executeAsync(search, factory, executor);
    }

    public CompletableFuture<SearchResult> executeAsync(FulltextSearch search, DocumentFactory factory, Executor executor) {
        return CompletableFuture.supplyAsync(() -> this.execute(search, factory), executor);
    }

    public <T> CompletableFuture<SuggestionResult> executeAsync(ExecutableSuggestionSearch search, Class<T> c) {
        return executeAsync(search, c, executor);
    }

    public <T> CompletableFuture<SuggestionResult> executeAsync(ExecutableSuggestionSearch search, Class<T> c, Executor executor) {
        return CompletableFuture.supplyAsync(() -> this.execute(search, c), executor);
    }

    public CompletableFuture<SuggestionResult> executeAsync(ExecutableSuggestionSearch search, DocumentFactory assets) {
        return executeAsync(search, assets, executor);
    }

    public CompletableFuture<SuggestionResult> executeAsync(ExecutableSuggestionSearch search, DocumentFactory assets, Executor executor) {
        return CompletableFuture.supplyAsync(() -> this.execute(search, assets), executor);
    }

    public static CompletableSearchServer getInstance(Executor executor) {
        return new CompletableSearchServer(SearchServer.getInstance(), executor);
    }

    public static CompletableSearchServer getInstance() {
        return new CompletableSearchServer(SearchServer.getInstance());
    }

    /* ******** OVERRIDES ******** */

    @Override
    public Object getBackend() {
        return backend.getBackend();
    }

    @Override
    public void index(Document ... docs) {
        backend.index(docs);
    }

    @Override
    public void index(List<Document> docs) {
        backend.index(docs);
    }

    @Override
    public void execute(Update update, DocumentFactory factory) {
        backend.execute(update, factory);
    }

    @Override
    public void execute(Delete delete, DocumentFactory factory) {
        backend.execute(delete, factory);
    }

    @Override
    public void delete(Document doc) {
        backend.delete(doc);
    }

    @Override
    public void commit(boolean optimize) {
        backend.commit(optimize);
    }

    @Override
    public <T> BeanSearchResult<T> execute(FulltextSearch search, Class<T> c) {
        return backend.execute(search, c);
    }

    @Override
    public SearchResult execute(FulltextSearch search, DocumentFactory factory) {
        return backend.execute(search, factory);
    }

    @Override
    public <T> SuggestionResult execute(ExecutableSuggestionSearch search, Class<T> c) {
        return backend.execute(search, c);
    }

    @Override
    public SuggestionResult execute(ExecutableSuggestionSearch search, DocumentFactory assets) {
        return backend.execute(search, assets);
    }

    @Override
    public SuggestionResult execute(ExecutableSuggestionSearch search, DocumentFactory assets, DocumentFactory childFactory) {
        return backend.execute(search, assets, childFactory);
    }

    @Override
    public <T> GetResult execute(RealTimeGet search, Class<T> c) {
        return backend.execute(search, c);
    }

    @Override
    public GetResult execute(RealTimeGet search, DocumentFactory assets) {
        return backend.execute(search, assets);
    }

    @Override
    public void clearIndex() {
        backend.clearIndex();
    }

    @Override
    public void close() {
        try {
            if (shutdownExecutorOnClose && executor instanceof ExecutorService) {
                ExecutorService executorService = (ExecutorService) this.executor;
                try {
                    executorService.shutdown();
                    // wait for running requests to complete
                    executorService.awaitTermination(60, SECONDS);
                } catch (InterruptedException e) {
                    log.warn("Got interrupted while waiting for running requests to complete.", e);
                    // restore interrupted flag
                    Thread.currentThread().interrupt();
                }
            }
        } finally {
            backend.close();
        }
    }

    @Override
    public Class getServiceProviderClass() {
        return backend.getServiceProviderClass();
    }

}
