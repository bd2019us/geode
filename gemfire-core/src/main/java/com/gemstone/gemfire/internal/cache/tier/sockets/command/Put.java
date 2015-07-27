/*=========================================================================
 * Copyright (c) 2010-2014 Pivotal Software, Inc. All Rights Reserved.
 * This product is protected by U.S. and international copyright
 * and intellectual property laws. Pivotal products are covered by
 * one or more patents listed at http://www.pivotal.io/patents.
 *=========================================================================
 */
/**
 * 
 */
package com.gemstone.gemfire.internal.cache.tier.sockets.command;

import com.gemstone.gemfire.internal.cache.EntryEventImpl;
import com.gemstone.gemfire.internal.cache.EventID;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.tier.CachedRegionHelper;
import com.gemstone.gemfire.internal.cache.tier.Command;
import com.gemstone.gemfire.internal.cache.tier.MessageType;
import com.gemstone.gemfire.internal.cache.tier.sockets.*;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.logging.log4j.LocalizedMessage;
import com.gemstone.gemfire.internal.security.AuthorizeRequest;
import com.gemstone.gemfire.security.GemFireSecurityException;
import com.gemstone.gemfire.cache.DynamicRegionFactory;
import com.gemstone.gemfire.cache.RegionDestroyedException;
import com.gemstone.gemfire.cache.ResourceException;
import com.gemstone.gemfire.cache.operations.PutOperationContext;
import com.gemstone.gemfire.distributed.internal.DistributionStats;
import com.gemstone.gemfire.i18n.StringId;

import java.io.IOException;
import java.nio.ByteBuffer;

public class Put extends BaseCommand {

  private final static Put singleton = new Put();

  public static Command getCommand() {
    return singleton;
  }

  private Put() {
  }

  @Override
  public void cmdExecute(Message msg, ServerConnection servConn, long start)
      throws IOException, InterruptedException {
    Part regionNamePart = null, keyPart = null, valuePart = null, callbackArgPart = null;
    String regionName = null;
    Object callbackArg = null, key = null;
    Part eventPart = null;
    String errMessage = "";
    CachedRegionHelper crHelper = servConn.getCachedRegionHelper();
    CacheServerStats stats = servConn.getCacheServerStats();
    if (crHelper.emulateSlowServer() > 0) {
      // this.logger.debug("SlowServer", new Exception());
      boolean interrupted = Thread.interrupted();
      try {
        Thread.sleep(crHelper.emulateSlowServer());
      }
      catch (InterruptedException ugh) {
        interrupted = true;
      }
      finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
      ;
    }

    // requiresResponse = true;
    servConn.setAsTrue(REQUIRES_RESPONSE);
    {
      long oldStart = start;
      start = DistributionStats.getStatTime();
      stats.incReadPutRequestTime(start - oldStart);
    }
    // Retrieve the data from the message parts
    regionNamePart = msg.getPart(0);
    keyPart = msg.getPart(1);
    valuePart = msg.getPart(2);
    eventPart = msg.getPart(3);
//    callbackArgPart = null; (redundant assignment)
    if (msg.getNumberOfParts() > 4) {
      callbackArgPart = msg.getPart(4);
      try {
        callbackArg = callbackArgPart.getObject();
      }
      catch (Exception e) {
        writeException(msg, e, false, servConn);
        servConn.setAsTrue(RESPONDED);
        return;
      }
    }
    regionName = regionNamePart.getString();

    try {
      key = keyPart.getStringOrObject();
    }
    catch (Exception e) {
      writeException(msg, e, false, servConn);
      servConn.setAsTrue(RESPONDED);
      return;
    }

    if (logger.isTraceEnabled()) {
      logger.trace("{}: Received put request ({} bytes) from {} for region {} key {} value {}", servConn.getName(), msg.getPayloadLength(), servConn.getSocketString(), regionName, key, valuePart);
    }

    // Process the put request
    if (key == null || regionName == null) {
      if (key == null) {
        logger.warn(LocalizedMessage.create(LocalizedStrings.Put_0_THE_INPUT_KEY_FOR_THE_PUT_REQUEST_IS_NULL, servConn.getName()));
        errMessage = LocalizedStrings.Put_THE_INPUT_KEY_FOR_THE_PUT_REQUEST_IS_NULL.toLocalizedString();
      }
      if (regionName == null) {
        logger.warn(LocalizedMessage.create(LocalizedStrings.Put_0_THE_INPUT_REGION_NAME_FOR_THE_PUT_REQUEST_IS_NULL, servConn.getName()));
        errMessage = LocalizedStrings.Put_THE_INPUT_REGION_NAME_FOR_THE_PUT_REQUEST_IS_NULL.toLocalizedString();
      }
      writeErrorResponse(msg, MessageType.PUT_DATA_ERROR,
          errMessage.toString(), servConn);
      servConn.setAsTrue(RESPONDED);
    }
    else {
      LocalRegion region = (LocalRegion)crHelper.getRegion(regionName);
      if (region == null) {
        String reason = LocalizedStrings.Put_REGION_WAS_NOT_FOUND_DURING_PUT_REQUEST.toLocalizedString();
        writeRegionDestroyedEx(msg, regionName, reason, servConn);
        servConn.setAsTrue(RESPONDED);
      }
      else if (valuePart.isNull() && region.containsKey(key)) {
        // Invalid to 'put' a null value in an existing key
        logger.info(LocalizedMessage.create(LocalizedStrings.Put_0_ATTEMPTED_TO_PUT_A_NULL_VALUE_FOR_EXISTING_KEY_1, new Object[] {servConn.getName(), key}));
        errMessage = LocalizedStrings.Put_ATTEMPTED_TO_PUT_A_NULL_VALUE_FOR_EXISTING_KEY_0.toLocalizedString();
        writeErrorResponse(msg, MessageType.PUT_DATA_ERROR, errMessage,
            servConn);
        servConn.setAsTrue(RESPONDED);
      }
      else {
          // try {
        // this.eventId = (EventID)eventPart.getObject();
        ByteBuffer eventIdPartsBuffer = ByteBuffer.wrap(eventPart
            .getSerializedForm());
        long threadId = EventID
            .readEventIdPartsFromOptmizedByteArray(eventIdPartsBuffer);
        long sequenceId = EventID
            .readEventIdPartsFromOptmizedByteArray(eventIdPartsBuffer);
        EventID eventId = new EventID(servConn.getEventMemberIDByteArray(),
            threadId, sequenceId);
        // } catch (Exception e) {
        // writeException(msg, e, false);
        // responded = true;
        // continue;
        // }
        try {
          byte[] value = valuePart.getSerializedForm();
          boolean isObject = valuePart.isObject();
          AuthorizeRequest authzRequest = servConn.getAuthzRequest();
          if (authzRequest != null) {
            // TODO SW: This is to handle DynamicRegionFactory create
            // calls. Rework this when the semantics of DynamicRegionFactory are
            // cleaned up.
            if (DynamicRegionFactory.regionIsDynamicRegionList(regionName)) {
              authzRequest.createRegionAuthorize((String)key);
            }
            // Allow PUT operations on meta regions (bug #38961)
            else if (!region.isUsedForMetaRegion()) {
              PutOperationContext putContext = authzRequest.putAuthorize(
                  regionName, key, value, isObject, callbackArg);
              value = putContext.getSerializedValue();
              isObject = putContext.isObject();
              callbackArg = putContext.getCallbackArg();
            }
          }
          // If the value is 1 byte and the byte represents null,
          // attempt to create the entry. This test needs to be
          // moved to DataSerializer or DataSerializer.NULL needs
          // to be publicly accessible.
          boolean result = false;
          if (value == null) {
            // Create the null entry. Since the value is null, the value of the
            // isObject
            // the true after null doesn't matter and is not used.
            result = region.basicBridgeCreate(key, null, true, callbackArg,
                servConn.getProxyID(), true, new EntryEventImpl(eventId), false);
          }
          else {
            // Put the entry
            result = region.basicBridgePut(key, value, null, isObject, callbackArg,
                servConn.getProxyID(), true, new EntryEventImpl(eventId), servConn.isSqlFabricSystem());
          }
          if (result) {
            servConn.setModificationInfo(true, regionName, key);
          }
          else {
            StringId message = LocalizedStrings.PUT_0_FAILED_TO_PUT_ENTRY_FOR_REGION_1_KEY_2_VALUE_3;
            Object[] messageArgs = new Object[] {servConn.getName(), regionName, key, valuePart};
            String s = message.toLocalizedString(messageArgs);
            logger.info(s);
            throw new Exception(s);
          }
        }
        catch (RegionDestroyedException rde) {
          writeException(msg, rde, false, servConn);
          servConn.setAsTrue(RESPONDED);
          return;
        }
        catch (ResourceException re) {
          writeException(msg, re, false, servConn);
          servConn.setAsTrue(RESPONDED);
          return;
        }
        catch (Exception ce) {
          // If an interrupted exception is thrown , rethrow it
          checkForInterrupt(servConn, ce);

          // If an exception occurs during the put, preserve the connection
          writeException(msg, ce, false, servConn);
          servConn.setAsTrue(RESPONDED);
          if (ce instanceof GemFireSecurityException) {
            // Fine logging for security exceptions since these are already
            // logged by the security logger
            if (logger.isDebugEnabled()) {
              logger.debug("{}: Unexpected Security exception", servConn.getName(), ce);
            }
          }
          else {
            logger.warn(LocalizedMessage.create(LocalizedStrings.PUT_0_UNEXPECTED_EXCEPTION, servConn.getName()), ce);
          }
          return;
        }
        finally {
          long oldStart = start;
          start = DistributionStats.getStatTime();
          stats.incProcessPutTime(start - oldStart);
        }

        // Increment statistics and write the reply
        writeReply(msg, servConn);
        
        servConn.setAsTrue(RESPONDED);
        if (logger.isDebugEnabled()) {
          logger.debug("{}: Sent put response back to {} for region {} key {} value {}", servConn.getName(), servConn.getSocketString(), regionName, key, valuePart);
        }
        stats.incWritePutResponseTime(DistributionStats.getStatTime() - start);
      }
    }

  }

}
