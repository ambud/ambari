/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ambari.server.serveraction.upgrades;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.actionmanager.HostRoleStatus;
import org.apache.ambari.server.agent.CommandReport;
import org.apache.ambari.server.api.services.AmbariMetaInfo;
import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.controller.AmbariManagementController;
import org.apache.ambari.server.controller.ConfigurationRequest;
import org.apache.ambari.server.orm.entities.RepositoryVersionEntity;
import org.apache.ambari.server.serveraction.ServerAction;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Config;
import org.apache.ambari.server.state.ConfigHelper;
import org.apache.ambari.server.state.ConfigMergeHelper;
import org.apache.ambari.server.state.ConfigMergeHelper.ThreeWayValue;
import org.apache.ambari.server.state.DesiredConfig;
import org.apache.ambari.server.state.PropertyInfo;
import org.apache.ambari.server.state.StackId;
import org.apache.ambari.server.state.UpgradeContext;
import org.apache.ambari.server.state.stack.upgrade.ConfigUpgradeChangeDefinition.ConfigurationKeyValue;
import org.apache.ambari.server.state.stack.upgrade.ConfigUpgradeChangeDefinition.Insert;
import org.apache.ambari.server.state.stack.upgrade.ConfigUpgradeChangeDefinition.Masked;
import org.apache.ambari.server.state.stack.upgrade.ConfigUpgradeChangeDefinition.Replace;
import org.apache.ambari.server.state.stack.upgrade.ConfigUpgradeChangeDefinition.Transfer;
import org.apache.ambari.server.state.stack.upgrade.ConfigureTask;
import org.apache.ambari.server.state.stack.upgrade.Direction;
import org.apache.ambari.server.state.stack.upgrade.PropertyKeyState;
import org.apache.ambari.server.state.stack.upgrade.TransferOperation;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Inject;
import com.google.inject.Provider;

/**
 * The {@link ConfigureAction} is used to alter a configuration property during
 * an upgrade. It will only produce a new configuration if an actual change is
 * occuring. For some configure tasks, the value is already at the desired
 * property or the conditions of the task are not met. In these cases, a new
 * configuration will not be created. This task can perform any of the following
 * actions in a single declaration:
 * <ul>
 * <li>Copy a configuration to a new property key, optionally setting a default
 * if the original property did not exist</li>
 * <li>Copy a configuration to a new property key from one configuration type to
 * another, optionally setting a default if the original property did not exist</li>
 * <li>Rename a configuration, optionally setting a default if the original
 * property did not exist</li>
 * <li>Delete a configuration property</li>
 * <li>Set a configuration property</li>
 * <li>Conditionally set a configuration property based on another configuration
 * property value</li>
 * </ul>
 */
public class ConfigureAction extends AbstractUpgradeServerAction {

  private static final Logger LOG = LoggerFactory.getLogger(ConfigureAction.class);

  /**
   * Used to update the configuration properties.
   */
  @Inject
  private AmbariManagementController m_controller;

  /**
   * Used to assist in the creation of a {@link ConfigurationRequest} to update
   * configuration values.
   */
  @Inject
  private ConfigHelper m_configHelper;

  /**
   * The Ambari configuration.
   */
  @Inject
  private Configuration m_configuration;

  /**
   * Used to lookup stack properties which are the configuration properties that
   * are defined on the stack.
   */
  @Inject
  private Provider<AmbariMetaInfo> m_ambariMetaInfo;

  @Inject
  private ConfigMergeHelper m_mergeHelper;

  /**
   * Gson
   */
  @Inject
  private Gson m_gson;

  /**
   * Aside from the normal execution, this method performs the following logic, with
   * the stack values set in the table below:
   * <p>
   * <table>
   *  <tr>
   *    <th>Upgrade Path</th>
   *    <th>direction</th>
   *    <th>Stack Actual</th>
   *    <th>Stack Desired</th>
   *    <th>Config Stack</th>
   *    <th>Action</th>
   *  </tr>
   *  <tr>
   *    <td>2.2.x -> 2.2.y</td>
   *    <td>upgrade or downgrade</td>
   *    <td>2.2</td>
   *    <td>2.2</td>
   *    <td>2.2</td>
   *    <td>if value has changed, create a new config object with new value</td>
   *  </tr>
   *  <tr>
   *    <td>2.2 -> 2.3</td>
   *    <td>upgrade</td>
   *    <td>2.2</td>
   *    <td>2.3: set before action is executed</td>
   *    <td>2.3: set before action is executed</td>
   *    <td>new configs are already created; just update with new properties</td>
   *  </tr>
   *  <tr>
   *    <td>2.3 -> 2.2</td>
   *    <td>downgrade</td>
   *    <td>2.2</td>
   *    <td>2.2: set before action is executed</td>
   *    <td>2.2</td>
   *    <td>configs are already managed, results are the same as 2.2.x -> 2.2.y</td>
   *  </tr>
   * </table>
   * </p>
   *
   * {@inheritDoc}
   */
  @Override
  public CommandReport execute(
      ConcurrentMap<String, Object> requestSharedDataContext)
      throws AmbariException, InterruptedException {

    Map<String,String> commandParameters = getCommandParameters();
    if( null == commandParameters || commandParameters.isEmpty() ){
      return createCommandReport(0, HostRoleStatus.FAILED, "{}", "",
          "Unable to change configuration values without command parameters");
    }

    String clusterName = commandParameters.get("clusterName");
    Cluster cluster = getClusters().getCluster(clusterName);
    UpgradeContext upgradeContext = getUpgradeContext(cluster);

    // such as hdfs-site or hbase-env
    String configType = commandParameters.get(ConfigureTask.PARAMETER_CONFIG_TYPE);
    String serviceName = cluster.getServiceByConfigType(configType);

    // !!! we couldn't get the service based on its config type, so try the associated
    if (StringUtils.isBlank(serviceName)) {
      serviceName = commandParameters.get(ConfigureTask.PARAMETER_ASSOCIATED_SERVICE);
    }

    RepositoryVersionEntity sourceRepoVersion = upgradeContext.getSourceRepositoryVersion(serviceName);
    RepositoryVersionEntity targetRepoVersion = upgradeContext.getTargetRepositoryVersion(serviceName);
    StackId sourceStackId = sourceRepoVersion.getStackId();
    StackId targetStackId = targetRepoVersion.getStackId();

    // extract setters
    List<ConfigurationKeyValue> keyValuePairs = Collections.emptyList();
    String keyValuePairJson = commandParameters.get(ConfigureTask.PARAMETER_KEY_VALUE_PAIRS);
    if (null != keyValuePairJson) {
      keyValuePairs = m_gson.fromJson(
          keyValuePairJson, new TypeToken<List<ConfigurationKeyValue>>(){}.getType());
      keyValuePairs = getAllowedSets(cluster, configType, keyValuePairs);
    }

    // extract transfers
    List<Transfer> transfers = Collections.emptyList();
    String transferJson = commandParameters.get(ConfigureTask.PARAMETER_TRANSFERS);
    if (null != transferJson) {
      transfers = m_gson.fromJson(
        transferJson, new TypeToken<List<Transfer>>(){}.getType());
      transfers = getAllowedTransfers(cluster, configType, transfers);
    }

    // extract replacements
    List<Replace> replacements = Collections.emptyList();
    String replaceJson = commandParameters.get(ConfigureTask.PARAMETER_REPLACEMENTS);
    if (null != replaceJson) {
      replacements = m_gson.fromJson(
          replaceJson, new TypeToken<List<Replace>>(){}.getType());
      replacements = getAllowedReplacements(cluster, configType, replacements);
    }

    // extract insertions
    List<Insert> insertions = Collections.emptyList();
    String insertJson = commandParameters.get(ConfigureTask.PARAMETER_INSERTIONS);
    if (null != insertJson) {
      insertions = m_gson.fromJson(
          insertJson, new TypeToken<List<Insert>>(){}.getType());
    }

    // if there is nothing to do, then skip the task
    if (keyValuePairs.isEmpty() && transfers.isEmpty() && replacements.isEmpty() && insertions.isEmpty()) {
      String message = "cluster={0}, type={1}, transfers={2}, replacements={3}, insertions={4}, configurations={5}";
      message = MessageFormat.format(message, clusterName, configType, transfers, replacements,
          insertions, keyValuePairs);

      StringBuilder buffer = new StringBuilder(
          "Skipping this configuration task since none of the conditions were met and there are no transfers, replacements, or insertions.").append("\n");

      buffer.append(message);

      return createCommandReport(0, HostRoleStatus.COMPLETED, "{}", buffer.toString(), "");
    }

    // if only 1 of the required properties was null and no transfer properties,
    // then something went wrong
    if (null == clusterName || null == configType
        || (keyValuePairs.isEmpty() && transfers.isEmpty() && replacements.isEmpty() && insertions.isEmpty())) {
      String message = "cluster={0}, type={1}, transfers={2}, replacements={3}, insertions={4}, configurations={5}";

      message = MessageFormat.format(message, clusterName, configType, transfers, replacements,
          insertions, keyValuePairs);

      return createCommandReport(0, HostRoleStatus.FAILED, "{}", "", message);
    }

    Map<String, DesiredConfig> desiredConfigs = cluster.getDesiredConfigs();
    DesiredConfig desiredConfig = desiredConfigs.get(configType);
    if (desiredConfig == null) {
      throw new AmbariException("Could not find desired config type with name " + configType);
    }

    Config config = cluster.getConfig(configType, desiredConfig.getTag());
    if (config == null) {
      throw new AmbariException("Could not find config type with name " + configType);
    }

    StackId configStack = config.getStackId();

    // !!! initial reference values
    Map<String, String> base = config.getProperties();
    Map<String, String> newValues = new HashMap<>(base);

    boolean changedValues = false;

    // !!! do transfers first before setting defined values
    StringBuilder outputBuffer = new StringBuilder(250);
    for (Transfer transfer : transfers) {
      switch (transfer.operation) {
        case COPY:
          String valueToCopy = null;
          if( null == transfer.fromType ) {
            // copying from current configuration
            valueToCopy = base.get(transfer.fromKey);
          } else {
            // copying from another configuration
            Config other = cluster.getDesiredConfigByType(transfer.fromType);
            if (null != other){
              Map<String, String> otherValues = other.getProperties();
              if (otherValues.containsKey(transfer.fromKey)){
                valueToCopy = otherValues.get(transfer.fromKey);
              }
            }
          }

          // if the value is null use the default if it exists
          if (StringUtils.isBlank(valueToCopy) && !StringUtils.isBlank(transfer.defaultValue)) {
            valueToCopy = transfer.defaultValue;
          }

          if (StringUtils.isNotBlank(valueToCopy)) {
            // possibly coerce the value on copy
            if (transfer.coerceTo != null) {
              switch (transfer.coerceTo) {
                case YAML_ARRAY: {
                  // turn c6401,c6402 into ['c6401',c6402']
                  String[] splitValues = StringUtils.split(valueToCopy, ',');
                  List<String> quotedValues = new ArrayList<>(splitValues.length);
                  for (String splitValue : splitValues) {
                    quotedValues.add("'" + StringUtils.trim(splitValue) + "'");
                  }

                  valueToCopy = "[" + StringUtils.join(quotedValues, ',') + "]";

                  break;
                }
                default:
                  break;
              }
            }

            // at this point we know that we have a changed value
            changedValues = true;
            newValues.put(transfer.toKey, valueToCopy);

            // append standard output
            updateBufferWithMessage(outputBuffer, MessageFormat.format("Created {0}/{1} = \"{2}\"",
                configType,
                transfer.toKey, mask(transfer, valueToCopy)));
          }
          break;
        case MOVE:
          // if the value existed previously, then update the maps with the new
          // key; otherwise if there is a default value specified, set the new
          // key with the default
          if (newValues.containsKey(transfer.fromKey)) {
            newValues.put(transfer.toKey, newValues.remove(transfer.fromKey));
            changedValues = true;

            // append standard output
            updateBufferWithMessage(outputBuffer,
                MessageFormat.format("Renamed {0}/{1} to {2}/{3}", configType,
                transfer.fromKey, configType, transfer.toKey));

          } else if (StringUtils.isNotBlank(transfer.defaultValue)) {
            newValues.put(transfer.toKey, transfer.defaultValue);
            changedValues = true;

            // append standard output
            updateBufferWithMessage(outputBuffer,
                MessageFormat.format("Created {0}/{1} with default value \"{2}\"",
                configType, transfer.toKey, mask(transfer, transfer.defaultValue)));
          }

          break;
        case DELETE:
          if ("*".equals(transfer.deleteKey)) {
            newValues.clear();

            // append standard output
            updateBufferWithMessage(outputBuffer,
                MessageFormat.format("Deleted all keys from {0}", configType));

            for (String keeper : transfer.keepKeys) {
              if (base.containsKey(keeper) && base.get(keeper) != null) {
                newValues.put(keeper, base.get(keeper));

                // append standard output
                updateBufferWithMessage(outputBuffer,
                    MessageFormat.format("Preserved {0}/{1} after delete", configType, keeper));
              }
            }

            // !!! with preserved edits, find the values that are different from
            // the stack-defined and keep them - also keep values that exist in
            // the config but not on the stack
            if (transfer.preserveEdits) {
              List<String> edited = findValuesToPreserve(clusterName, config);
              for (String changed : edited) {
                newValues.put(changed, base.get(changed));

                // append standard output
                updateBufferWithMessage(outputBuffer,
                    MessageFormat.format("Preserved {0}/{1} after delete",
                    configType, changed));
              }
            }

            changedValues = true;
          } else {
            newValues.remove(transfer.deleteKey);
            changedValues = true;

            // append standard output
            updateBufferWithMessage(outputBuffer,
                MessageFormat.format("Deleted {0}/{1}", configType,
                transfer.deleteKey));
          }

          break;
      }
    }

    // set all key/value pairs
    if (null != keyValuePairs && !keyValuePairs.isEmpty()) {
      for (ConfigurationKeyValue keyValuePair : keyValuePairs) {
        String key = keyValuePair.key;
        String value = keyValuePair.value;

        if (null != key) {
          String oldValue = base.get(key);

          // !!! values are not changing, so make this a no-op
          if (StringUtils.equals(value, oldValue)) {
            if (sourceStackId.equals(targetStackId) && !changedValues) {
              updateBufferWithMessage(outputBuffer,
                  MessageFormat.format(
                  "{0}/{1} for cluster {2} would not change, skipping setting", configType, key,
                  clusterName));

              // continue because this property is not changing
              continue;
            }
          }

          // !!! only put a key/value into this map of new configurations if
          // there was a key, otherwise this will put something like null=null
          // into the configs which will cause NPEs after upgrade - this is a
          // byproduct of the configure being able to take a list of transfers
          // without a key/value to set
          newValues.put(key, value);

          final String message;
          if (StringUtils.isEmpty(value)) {
            message = MessageFormat.format("{0}/{1} changed to an empty value", configType, key);
          } else {
            message = MessageFormat.format("{0}/{1} changed to \"{2}\"", configType, key,
                mask(keyValuePair, value));
          }

          updateBufferWithMessage(outputBuffer, message);
        }
      }
    }

    // replacements happen only on the new values (as they are initialized from
    // the existing pre-upgrade values)
    for (Replace replacement : replacements) {
      // the key might exist but might be null, so we need to check this
      // condition when replacing a part of the value
      String toReplace = newValues.get(replacement.key);
      if (StringUtils.isNotBlank(toReplace)) {
        if (!toReplace.contains(replacement.find)) {
          updateBufferWithMessage(outputBuffer,
              MessageFormat.format("String \"{0}\" was not found in {1}/{2}",
              replacement.find, configType, replacement.key));
        } else {
          String replaced = StringUtils.replace(toReplace, replacement.find, replacement.replaceWith);

          newValues.put(replacement.key, replaced);

          updateBufferWithMessage(outputBuffer,
              MessageFormat.format("Replaced {0}/{1} containing \"{2}\" with \"{3}\"", configType,
                  replacement.key, replacement.find, replacement.replaceWith));
        }
      } else {
        updateBufferWithMessage(outputBuffer, MessageFormat.format(
            "Skipping replacement for {0}/{1} because it does not exist or is empty.",
            configType, replacement.key));
      }
    }

    // insertions happen only on the new values (as they are initialized from
    // the existing pre-upgrade values)
    for (Insert insert : insertions) {
      String valueToInsertInto = newValues.get(insert.key);

      // if the key doesn't exist, then do no work
      if (StringUtils.isNotBlank(valueToInsertInto)) {
        // make this insertion idempotent - don't do it if the value already
        // contains the content
        if (StringUtils.contains(valueToInsertInto, insert.value)) {
          updateBufferWithMessage(outputBuffer,
              MessageFormat.format("Skipping insertion for {0}/{1} because it already contains {2}",
                  configType, insert.key, insert.value));

          continue;
        }

        // new line work
        String valueToInsert = insert.value;
        if (insert.newlineBefore) {
          valueToInsert = System.lineSeparator() + valueToInsert;
        }

        // new line work
        if (insert.newlineAfter) {
          valueToInsert = valueToInsert + System.lineSeparator();
        }

        switch (insert.insertType) {
          case APPEND:
            valueToInsertInto = valueToInsertInto + valueToInsert;
            break;
          case PREPEND:
            valueToInsertInto = valueToInsert + valueToInsertInto;
            break;
          default:
            LOG.error("Unable to insert {0}/{1} with unknown insertion type of {2}", configType,
                insert.key, insert.insertType);
            break;
        }

        newValues.put(insert.key, valueToInsertInto);

        updateBufferWithMessage(outputBuffer, MessageFormat.format(
            "Updated {0}/{1} by inserting {2}", configType, insert.key, insert.value));
      } else {
        updateBufferWithMessage(outputBuffer, MessageFormat.format(
            "Skipping insertion for {0}/{1} because it does not exist or is empty.", configType,
            insert.key));
      }
    }

    // !!! check to see if we're going to a new stack and double check the
    // configs are for the target.  Then simply update the new properties instead
    // of creating a whole new history record since it was already done
    if (!targetStackId.equals(sourceStackId) && targetStackId.equals(configStack)) {
      config.setProperties(newValues);
      config.save();

      return createCommandReport(0, HostRoleStatus.COMPLETED, "{}", outputBuffer.toString(), "");
    }

    // !!! values are different and within the same stack.  create a new
    // config and service config version
    Direction direction = upgradeContext.getDirection();
    String serviceVersionNote = String.format("%s %s %s", direction.getText(true),
        direction.getPreposition(), upgradeContext.getRepositoryVersion().getVersion());

    String auditName = getExecutionCommand().getRoleParams().get(ServerAction.ACTION_USER_NAME);

    if (auditName == null) {
      auditName = m_configuration.getAnonymousAuditName();
    }

    m_configHelper.createConfigType(cluster, targetStackId, m_controller, configType,
        newValues, auditName, serviceVersionNote);

    return createCommandReport(0, HostRoleStatus.COMPLETED, "{}", outputBuffer.toString(), "");
  }


  /**
   * Finds the values that should be preserved during a delete. This includes:
   * <ul>
   * <li>Properties that existed on the stack but were changed to a different
   * value</li>
   * <li>Properties that do not exist on the stack</li>
   * </ul>
   *
   * @param clusterName
   *          the cluster name
   * @param config
   *          the config with the tag to find conflicts
   * @return the list of changed property keys
   * @throws AmbariException
   */
  private List<String> findValuesToPreserve(String clusterName, Config config)
      throws AmbariException {
    List<String> result = new ArrayList<>();

    Map<String, Map<String, ThreeWayValue>> conflicts =
        m_mergeHelper.getConflicts(clusterName, config.getStackId());

    Map<String, ThreeWayValue> conflictMap = conflicts.get(config.getType());

    // process the conflicts, if any, and add them to the list
    if (null != conflictMap && !conflictMap.isEmpty()) {
      for (Map.Entry<String, ThreeWayValue> entry : conflictMap.entrySet()) {
        ThreeWayValue twv = entry.getValue();
        if (null == twv.oldStackValue) {
          result.add(entry.getKey());
        } else if (null != twv.savedValue && !twv.oldStackValue.equals(twv.savedValue)) {
          result.add(entry.getKey());
        }
      }
    }


    String configType = config.getType();
    Cluster cluster = getClusters().getCluster(clusterName);
    StackId oldStack = cluster.getCurrentStackVersion();

    // iterate over all properties for every cluster service; if the property
    // has the correct config type (ie oozie-site or hdfs-site) then add it to
    // the list of original stack propertiess
    Set<String> stackPropertiesForType = new HashSet<>(50);
    for (String serviceName : cluster.getServices().keySet()) {
      Set<PropertyInfo> serviceProperties = m_ambariMetaInfo.get().getServiceProperties(
          oldStack.getStackName(), oldStack.getStackVersion(), serviceName);

      for (PropertyInfo property : serviceProperties) {
        String type = ConfigHelper.fileNameToConfigType(property.getFilename());
        if (type.equals(configType)) {
          stackPropertiesForType.add(property.getName());
        }
      }
    }

    // now iterate over all stack properties, adding them to the list if they
    // match
    Set<PropertyInfo> stackProperties = m_ambariMetaInfo.get().getStackProperties(
        oldStack.getStackName(),
        oldStack.getStackVersion());

    for (PropertyInfo property : stackProperties) {
      String type = ConfigHelper.fileNameToConfigType(property.getFilename());
      if (type.equals(configType)) {
        stackPropertiesForType.add(property.getName());
      }
    }

    // see if any keys exist in the old config but not the the original stack
    // for this config type; that means they were added and should be preserved
    Map<String, String> base = config.getProperties();
    Set<String> baseKeys = base.keySet();
    for( String baseKey : baseKeys ){
      if (!stackPropertiesForType.contains(baseKey)) {
        result.add(baseKey);
      }
    }

    return result;
  }

  private static String mask(Masked mask, String value) {
    if (mask.mask) {
      return StringUtils.repeat("*", value.length());
    }
    return value;
  }

  private List<Replace> getAllowedReplacements(Cluster cluster, String configType, List<Replace> replacements){
    List<Replace> allowedReplacements= new ArrayList<>();

    for(Replace replacement: replacements){
      if(isOperationAllowed(cluster, configType, replacement.key,
          replacement.ifKey, replacement.ifType, replacement.ifValue, replacement.ifKeyState)) {
        allowedReplacements.add(replacement);
      }
    }

    return allowedReplacements;
  }

  private List<ConfigurationKeyValue> getAllowedSets(Cluster cluster, String configType, List<ConfigurationKeyValue> sets){
    List<ConfigurationKeyValue> allowedSets = new ArrayList<>();

    for(ConfigurationKeyValue configurationKeyValue: sets){
      if(isOperationAllowed(cluster, configType, configurationKeyValue.key,
          configurationKeyValue.ifKey, configurationKeyValue.ifType, configurationKeyValue.ifValue, configurationKeyValue.ifKeyState)) {
        allowedSets.add(configurationKeyValue);
      }
    }

    return allowedSets;
  }

  private List<Transfer> getAllowedTransfers(Cluster cluster, String configType, List<Transfer> transfers){
    List<Transfer> allowedTransfers = new ArrayList<>();
    for (Transfer transfer : transfers) {
      String key = "";
      if(transfer.operation == TransferOperation.DELETE) {
        key = transfer.deleteKey;
      } else {
        key = transfer.fromKey;
      }

      if(isOperationAllowed(cluster, configType, key,
          transfer.ifKey, transfer.ifType, transfer.ifValue, transfer.ifKeyState)) {
        allowedTransfers.add(transfer);
      }
    }

    return allowedTransfers;
  }

  private boolean isOperationAllowed(Cluster cluster, String configType, String targetPropertyKey,
      String ifKey, String ifType, String ifValue, PropertyKeyState ifKeyState){
    boolean isAllowed = true;

    boolean ifKeyIsNotBlank = StringUtils.isNotBlank(ifKey);
    boolean ifTypeIsNotBlank = StringUtils.isNotBlank(ifType);

    if (ifKeyIsNotBlank && ifTypeIsNotBlank && ifKeyState == PropertyKeyState.ABSENT) {
      boolean keyPresent = getDesiredConfigurationKeyPresence(cluster, ifType, ifKey);
      if (keyPresent) {
        LOG.info("Skipping property operation for {}/{} as the key {} for {} is present",
          configType, targetPropertyKey, ifKey, ifType);
        isAllowed = false;
      }
    } else if (ifKeyIsNotBlank && ifTypeIsNotBlank && ifValue == null &&
      ifKeyState == PropertyKeyState.PRESENT) {
      boolean keyPresent = getDesiredConfigurationKeyPresence(cluster, ifType, ifKey);
      if (!keyPresent) {
        LOG.info("Skipping property operation for {}/{} as the key {} for {} is not present",
          configType, targetPropertyKey, ifKey, ifType);
        isAllowed = false;
      }
    } else if (ifKeyIsNotBlank && ifTypeIsNotBlank && ifValue != null) {

      String ifConfigType = ifType;
      String checkValue = getDesiredConfigurationValue(cluster, ifConfigType, ifKey);
      if (!ifValue.toLowerCase().equals(StringUtils.lowerCase(checkValue))) {
        // skip adding
        LOG.info("Skipping property operation for {}/{} as the value {} for {}/{} is not equal to {}",
                 configType, targetPropertyKey, checkValue, ifConfigType, ifKey, ifValue);
        isAllowed = false;
      }
    }

    return isAllowed;
  }

  /**
   * Gets the property presence state
   * @param cluster
   *          the cluster (not {@code null}).
   * @param configType
   *          the configuration type (ie hdfs-site) (not {@code null}).
   * @param propertyKey
   *          the key to retrieve (not {@code null}).
   * @return {@code true} if property key exists or {@code false} if not.
   */
  private boolean getDesiredConfigurationKeyPresence(Cluster cluster,
      String configType, String propertyKey) {

    Map<String, DesiredConfig> desiredConfigs = cluster.getDesiredConfigs();
    DesiredConfig desiredConfig = desiredConfigs.get(configType);
    if (null == desiredConfig) {
      return false;
    }

    Config config = cluster.getConfig(configType, desiredConfig.getTag());
    if (null == config) {
      return false;
    }
    return config.getProperties().containsKey(propertyKey);
  }

  /**
   * Gets the value of the specified cluster property.
   *
   * @param cluster
   *          the cluster (not {@code null}).
   * @param configType
   *          the configuration type (ie hdfs-site) (not {@code null}).
   * @param propertyKey
   *          the key to retrieve (not {@code null}).
   * @return the value or {@code null} if it does not exist.
   */
  private String getDesiredConfigurationValue(Cluster cluster,
      String configType, String propertyKey) {

    Map<String, DesiredConfig> desiredConfigs = cluster.getDesiredConfigs();
    DesiredConfig desiredConfig = desiredConfigs.get(configType);
    if (null == desiredConfig) {
      return null;
    }

    Config config = cluster.getConfig(configType, desiredConfig.getTag());
    if (null == config) {
      return null;
    }

    return config.getProperties().get(propertyKey);
  }

  /**
   * Appends the buffer with the message as well as a newline.
   *
   * @param buffer
   * @param message
   */
  private void updateBufferWithMessage(StringBuilder buffer, String message) {
    buffer.append(message).append(System.lineSeparator());
  }
}
