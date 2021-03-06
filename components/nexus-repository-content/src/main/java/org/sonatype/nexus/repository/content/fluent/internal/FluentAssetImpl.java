/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.repository.content.fluent.internal;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobMetrics;
import org.sonatype.nexus.blobstore.api.BlobRef;
import org.sonatype.nexus.common.collect.AttributesMap;
import org.sonatype.nexus.common.collect.NestedAttributesMap;
import org.sonatype.nexus.common.hash.HashAlgorithm;
import org.sonatype.nexus.repository.MissingBlobException;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.cache.CacheController;
import org.sonatype.nexus.repository.cache.CacheInfo;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.AssetBlob;
import org.sonatype.nexus.repository.content.Component;
import org.sonatype.nexus.repository.content.facet.ContentFacetSupport;
import org.sonatype.nexus.repository.content.fluent.AttributeChange;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.content.store.AssetBlobData;
import org.sonatype.nexus.repository.content.store.AssetData;
import org.sonatype.nexus.repository.content.store.WrappedContent;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.payloads.BlobPayload;
import org.sonatype.nexus.repository.view.payloads.TempBlob;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.common.hash.HashCode;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static org.sonatype.nexus.blobstore.api.BlobStore.BLOB_NAME_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.CONTENT_TYPE_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.CREATED_BY_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.CREATED_BY_IP_HEADER;
import static org.sonatype.nexus.common.time.DateHelper.toLocalDateTime;
import static org.sonatype.nexus.repository.cache.CacheInfo.CACHE;
import static org.sonatype.nexus.repository.cache.CacheInfo.CACHE_TOKEN;
import static org.sonatype.nexus.repository.cache.CacheInfo.INVALIDATED;
import static org.sonatype.nexus.repository.content.fluent.AttributeChange.OVERLAY;
import static org.sonatype.nexus.repository.content.fluent.AttributeChange.SET;
import static org.sonatype.nexus.repository.content.fluent.internal.FluentAttributesHelper.applyAttributeChange;
import static org.sonatype.nexus.repository.storage.Bucket.REPO_NAME_HEADER;

/**
 * {@link FluentAsset} implementation.
 *
 * @since 3.next
 */
public class FluentAssetImpl
    implements FluentAsset, WrappedContent<Asset>
{
  private final ContentFacetSupport facet;

  private Asset asset;

  public FluentAssetImpl(final ContentFacetSupport facet, final Asset asset) {
    this.facet = checkNotNull(facet);
    this.asset = checkNotNull(asset);
  }

  @Override
  public Repository repository() {
    return facet.repository();
  }

  @Override
  public String path() {
    return asset.path();
  }

  @Override
  public String kind() {
    return asset.kind();
  }

  @Override
  public Optional<Component> component() {
    return asset.component();
  }

  @Override
  public Optional<AssetBlob> blob() {
    return asset.blob();
  }

  @Override
  public Optional<LocalDateTime> lastDownloaded() {
    return asset.lastDownloaded();
  }

  @Override
  public NestedAttributesMap attributes() {
    return asset.attributes();
  }

  @Override
  public LocalDateTime created() {
    return asset.created();
  }

  @Override
  public LocalDateTime lastUpdated() {
    return asset.lastUpdated();
  }

  @Override
  public FluentAsset attributes(final AttributeChange change, final String key, final Object value) {
    if (applyAttributeChange(asset, change, key, value)) {
      facet.stores().assetStore.updateAssetAttributes(asset);
    }
    return this;
  }

  @Override
  public FluentAsset attach(final TempBlob tempBlob) {
    facet.checkAttachAllowed(asset);
    return doAttach(makePermanent(tempBlob.getBlob()), tempBlob.getHashes());
  }

  @Override
  public FluentAsset attach(final Blob blob, final Map<HashAlgorithm, HashCode> checksums) {
    facet.checkAttachAllowed(asset);
    return doAttach(blob, checksums);
  }

  @Override
  public Content download() {
    AssetBlob assetBlob = asset.blob()
        .orElseThrow(() -> new IllegalStateException("No blob attached to " + asset.path()));

    BlobRef blobRef = assetBlob.blobRef();
    Blob blob = facet.stores().blobStore.get(blobRef.getBlobId());
    if (blob == null) {
      throw new MissingBlobException(blobRef);
    }

    Content content = new Content(new BlobPayload(blob, assetBlob.contentType()));

    AttributesMap contentAttributes = content.getAttributes();
    contentAttributes.set(Asset.class, this);

    return content;
  }

  @Override
  public FluentAsset markAsDownloaded() {
    facet.stores().assetStore.markAsDownloaded(asset);
    return this;
  }

  @Override
  public FluentAsset markAsCached(final CacheInfo cacheInfo) {
    return attributes(SET, CACHE, cacheInfo.toMap());
  }

  @Override
  public FluentAsset markAsStale() {
    return attributes(OVERLAY, CACHE, ImmutableMap.of(CACHE_TOKEN, INVALIDATED));
  }

  @Override
  public boolean isStale(final CacheController cacheController) {
    CacheInfo cacheInfo = CacheInfo.fromMap(attributes(CACHE));
    return cacheInfo != null && cacheController.isStale(cacheInfo);
  }

  @Override
  public boolean delete() {
    facet.checkDeleteAllowed(asset);
    return facet.stores().assetStore.deleteAsset(asset);
  }

  @Override
  public Asset unwrap() {
    return asset;
  }

  private AssetBlobData createAssetBlob(final BlobRef blobRef,
                                        final Blob blob,
                                        final Map<HashAlgorithm, HashCode> checksums)
  {
    BlobMetrics metrics = blob.getMetrics();
    Map<String, String> headers = blob.getHeaders();

    AssetBlobData assetBlob = new AssetBlobData();
    assetBlob.setBlobRef(blobRef);
    assetBlob.setBlobSize(metrics.getContentSize());
    assetBlob.setContentType(headers.get(CONTENT_TYPE_HEADER));

    assetBlob.setChecksums(checksums.entrySet().stream().collect(
        toImmutableMap(
            e -> e.getKey().name(),
            e -> e.getValue().toString())));

    assetBlob.setBlobCreated(toLocalDateTime(metrics.getCreationTime()));
    assetBlob.setCreatedBy(headers.get(CREATED_BY_HEADER));
    assetBlob.setCreatedByIp(headers.get(CREATED_BY_IP_HEADER));

    facet.stores().assetBlobStore.createAssetBlob(assetBlob);

    return assetBlob;
  }

  private BlobRef blobRef(final Blob blob) {
    return new BlobRef(facet.nodeName(), facet.stores().blobStoreName, blob.getId().asUniqueString());
  }

  private Blob makePermanent(final Blob tempBlob) {
    Builder<String, String> headers = ImmutableMap.builder();

    Map<String, String> tempHeaders = tempBlob.getHeaders();
    headers.put(REPO_NAME_HEADER, tempHeaders.get(REPO_NAME_HEADER));
    headers.put(BLOB_NAME_HEADER, asset.path());
    headers.put(CREATED_BY_HEADER, tempHeaders.get(CREATED_BY_HEADER));
    headers.put(CREATED_BY_IP_HEADER, tempHeaders.get(CREATED_BY_IP_HEADER));
    headers.put(CONTENT_TYPE_HEADER, facet.checkContentType(asset, tempBlob));

    return facet.stores().blobStore.copy(tempBlob.getId(), headers.build());
  }

  private FluentAsset doAttach(final Blob blob, final Map<HashAlgorithm, HashCode> checksums) {

    BlobRef blobRef = blobRef(blob);
    AssetBlob assetBlob = facet.stores().assetBlobStore.readAssetBlob(blobRef)
        .orElseGet(() -> createAssetBlob(blobRef, blob, checksums));

    ((AssetData) asset).setAssetBlob(assetBlob);
    facet.stores().assetStore.updateAssetBlobLink(asset);

    return this;
  }
}
