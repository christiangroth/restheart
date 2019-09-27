/*
 * RESTHeart - the Web API for MongoDB
 * Copyright (C) SoftInstigate Srl
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.restheart.handlers.collection;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.restheart.db.DocumentDAO;
import org.restheart.db.OperationResult;
import org.restheart.handlers.PipedHttpHandler;
import org.restheart.handlers.RequestContext;
import org.restheart.handlers.RequestContext.DOC_ID_TYPE;
import org.restheart.representation.RepUtils;
import org.restheart.utils.HttpStatus;
import org.restheart.utils.ResponseHelper;
import org.restheart.utils.URLUtils;

/**
 *
 * @author Andrea Di Cesare {@literal <andrea@softinstigate.com>}
 */
public class PostCollectionHandler extends PipedHttpHandler {

    private final DocumentDAO documentDAO;

    /**
     * Creates a new instance of PostCollectionHandler
     */
    public PostCollectionHandler() {
        this(null, new DocumentDAO());
    }

    public PostCollectionHandler(DocumentDAO documentDAO) {
        this(null, new DocumentDAO());
    }

    public PostCollectionHandler(PipedHttpHandler next) {
        this(next, new DocumentDAO());
    }

    public PostCollectionHandler(
            PipedHttpHandler next,
            DocumentDAO documentDAO) {
        super(next);
        this.documentDAO = documentDAO;
    }

    /**
     *
     * @param exchange
     * @param context
     * @throws Exception
     */
    @Override
    public void handleRequest(
            HttpServerExchange exchange,
            RequestContext context) throws Exception {
        if (context.isInError()) {
            next(exchange, context);
            return;
        }

        BsonValue _content = context.getContent();

        if (_content == null) {
            _content = new BsonDocument();
        }

        // cannot POST an array
        if (!_content.isDocument()) {
            ResponseHelper.endExchangeWithMessage(
                    exchange,
                    context,
                    HttpStatus.SC_NOT_ACCEPTABLE,
                    "data must be a json object");
            next(exchange, context);
            return;
        }

        BsonDocument content = _content.asDocument();

        if (content.containsKey("_id")
                && content.get("_id").isString()
                && RequestContext.isReservedResourceDocument(
                        context.getType(),
                        content.get("_id").asString().getValue())) {
            ResponseHelper.endExchangeWithMessage(
                    exchange,
                    context,
                    HttpStatus.SC_FORBIDDEN,
                    "reserved resource");
            next(exchange, context);
            return;
        }

        // if _id is not in content, it will be autogenerated as an ObjectId
        // check if the doc_type is different
        if (!content.containsKey("_id")) {
            if (!(context.getDocIdType() == DOC_ID_TYPE.OID)
                    && !(context.getDocIdType() == DOC_ID_TYPE.STRING_OID)) {
                ResponseHelper.endExchangeWithMessage(
                        exchange,
                        context,
                        HttpStatus.SC_NOT_ACCEPTABLE,
                        "_id in content body is mandatory "
                        + "for documents with id type "
                        + context.getDocIdType().name());
                next(exchange, context);
                return;
            }
        }

        OperationResult result = this.documentDAO
                .upsertDocumentPost(
                        context.getClientSession(),
                        context.getDBName(),
                        context.getCollectionName(),
                        context.getFiltersDocument(),
                        context.getShardKey(),
                        content,
                        context.getETag(),
                        context.isETagCheckRequired());

        context.setDbOperationResult(result);

        // inject the etag
        if (result.getEtag() != null) {
            ResponseHelper.injectEtagHeader(exchange, result.getEtag());
        }

        if (result.getHttpCode() == HttpStatus.SC_CONFLICT) {
            ResponseHelper.endExchangeWithMessage(
                    exchange,
                    context,
                    HttpStatus.SC_CONFLICT,
                    "The document's ETag must be provided using the '"
                    + Headers.IF_MATCH
                    + "' header.");

            next(exchange, context);
            return;
        }
        
        // handle the case of duplicate key error
        if (result.getHttpCode() == HttpStatus.SC_EXPECTATION_FAILED) {
            ResponseHelper.endExchangeWithMessage(
                    exchange,
                    context,
                    HttpStatus.SC_EXPECTATION_FAILED,
                    ResponseHelper.getMessageFromErrorCode(11000));
            next(exchange, context);
            return;
        }

        context.setResponseStatusCode(result.getHttpCode());

        // insert the Location handler for new documents
        // note, next handlers might change the status code
        if (result.getHttpCode() == HttpStatus.SC_CREATED) {
            exchange.getResponseHeaders()
                    .add(HttpString.tryFromString("Location"),
                            RepUtils.getReferenceLink(
                                    context,
                                    URLUtils.getRemappedRequestURL(exchange),
                                    result.getNewData().get("_id")));
        }

        next(exchange, context);
    }
}
