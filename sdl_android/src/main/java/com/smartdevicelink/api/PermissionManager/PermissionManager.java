package com.smartdevicelink.api.PermissionManager;

import android.support.annotation.IntDef;
import android.support.annotation.NonNull;

import com.smartdevicelink.api.BaseSubManager;
import com.smartdevicelink.protocol.enums.FunctionID;
import com.smartdevicelink.proxy.RPCNotification;
import com.smartdevicelink.proxy.interfaces.ISdl;
import com.smartdevicelink.proxy.rpc.OnHMIStatus;
import com.smartdevicelink.proxy.rpc.OnPermissionsChange;
import com.smartdevicelink.proxy.rpc.PermissionItem;
import com.smartdevicelink.proxy.rpc.enums.HMILevel;
import com.smartdevicelink.proxy.rpc.listeners.OnRPCNotificationListener;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 SdlManager gives the developer information about what permissions are permitted in specific HMI level
 and helps developers setup listeners to be called when specific permissions become allowed.
**/

 public class PermissionManager extends BaseSubManager{

    private HMILevel currentHMILevel;
    private Map<FunctionID, PermissionItem> currentPermissionItems;
    private OnRPCNotificationListener onHMIStatusListener, onPermissionsChangeListener;
    private List<PermissionFilter> filters;

    // Permission groups status constants
    @IntDef({PERMISSION_GROUP_STATUS_ALLOWED, PERMISSION_GROUP_STATUS_DISALLOWED,
            PERMISSION_GROUP_STATUS_MIXED, PERMISSION_GROUP_STATUS_UNKNOWN})
    @Retention(RetentionPolicy.SOURCE)
    public @interface PermissionGroupStatus {}
    public static final int PERMISSION_GROUP_STATUS_ALLOWED = 0;    // Every permission in the group is currently allowed
    public static final int PERMISSION_GROUP_STATUS_DISALLOWED = 1; // Every permission in the group is currently disallowed
    public static final int PERMISSION_GROUP_STATUS_MIXED = 2;      // Some permissions in the group are allowed and some disallowed
    public static final int PERMISSION_GROUP_STATUS_UNKNOWN = 3;    // The current status of the group is unknown

    // Permission groups type constants
    @IntDef({PERMISSION_GROUP_TYPE_ALL_ALLOWED, PERMISSION_GROUP_TYPE_ANY})
    @Retention(RetentionPolicy.SOURCE)
    public @interface PermissionGroupType {}
    public static final int PERMISSION_GROUP_TYPE_ALL_ALLOWED = 0;  // Be notified when all of the permission in the group are allowed, or, when they all stop being allowed in some sense, that is, when they were all allowed, and now they are not.
    public static final int PERMISSION_GROUP_TYPE_ANY = 1;          // Be notified when any change in availability occurs among the group

    /**
     * Creates a new instance of the PermissionManager
     * @param internalInterface
     */
    public PermissionManager(@NonNull ISdl internalInterface){
        super(internalInterface);
        transitionToState(SETTING_UP);
        this.currentHMILevel = null;
        this.currentPermissionItems = new HashMap<>();
        this.filters = new ArrayList<>();
        this.onHMIStatusListener = null;
        this.onPermissionsChangeListener = null;

        // Set PermissionManager's OnHMIStatusListener to keep currentHMILevel updated and call developer's listeners if needed
        onHMIStatusListener = new OnRPCNotificationListener() {
            @Override
            public void onNotified(RPCNotification notification) {
                if (currentHMILevel == null && getState() == SETTING_UP){
                    transitionToState(READY);
                }
                HMILevel previousHMILevel = currentHMILevel;
                currentHMILevel = ((OnHMIStatus)notification).getHmiLevel();
                callAffectedListeners(currentPermissionItems, previousHMILevel, currentPermissionItems, currentHMILevel);
            }
        };
        internalInterface.addOnRPCNotificationListener(FunctionID.ON_HMI_STATUS, onHMIStatusListener);

        // Set PermissionManager's PermissionsChangeListener to keep currentPermissionItems updated and call developer's listeners if needed
        onPermissionsChangeListener = new OnRPCNotificationListener() {
            @Override
            public void onNotified(RPCNotification notification) {
                List<PermissionItem> permissionItems = ((OnPermissionsChange)notification).getPermissionItem();
                Map<FunctionID, PermissionItem> previousPermissionItems = currentPermissionItems;
                currentPermissionItems = new HashMap<>();
                for (PermissionItem permissionItem : permissionItems) {
                    currentPermissionItems.put(FunctionID.getEnumForString(permissionItem.getRpcName()), permissionItem);
                }
                callAffectedListeners(previousPermissionItems, currentHMILevel, currentPermissionItems, currentHMILevel);
                previousPermissionItems.clear();
            }
        };
        internalInterface.addOnRPCNotificationListener(FunctionID.ON_PERMISSIONS_CHANGE, onPermissionsChangeListener);
    }

    /**
     * Go over all developer's listeners and call them if needed because of HMI level change or permission items change
     * @param previousPermissionItems
     * @param previousHmiLevel
     * @param currentPermissionItems
     * @param currentHMILevel
     */
    private void callAffectedListeners(Map<FunctionID, PermissionItem> previousPermissionItems, HMILevel previousHmiLevel, Map<FunctionID, PermissionItem> currentPermissionItems, HMILevel currentHMILevel){
        for (PermissionFilter filter : filters) {
            boolean anyChange = false;
            boolean allWereAllowed = true;
            boolean allNowAllowed = true;
            for (PermissionElement permissionElement : filter.getPermissionElements()) {
                // If at any point this condition is satisfied, then we don't need to continue
                if (anyChange && !allWereAllowed && !allNowAllowed){
                    break;
                }
                boolean rpcWasAllowed = isRPCAllowed(permissionElement.getRpcName(), previousPermissionItems, previousHmiLevel);
                boolean rpcNowAllowed = isRPCAllowed(permissionElement.getRpcName(), currentPermissionItems, currentHMILevel);
                if (rpcWasAllowed != rpcNowAllowed){
                    anyChange = true;
                }
                if (!rpcWasAllowed){
                    allWereAllowed = false;
                }
                if (!rpcNowAllowed){
                    allNowAllowed = false;
                }
                if (permissionElement.getParameters() != null && permissionElement.getParameters().size() > 0) {
                    for (String parameter : permissionElement.getParameters()) {
                        boolean parameterWasAllowed = isPermissionParameterAllowed(permissionElement.getRpcName(), parameter, previousPermissionItems, previousHmiLevel);
                        boolean parameterNowAllowed = isPermissionParameterAllowed(permissionElement.getRpcName(), parameter, currentPermissionItems, currentHMILevel);
                        if (parameterWasAllowed != parameterNowAllowed){
                            anyChange = true;
                        }
                        if (!parameterWasAllowed){
                            allWereAllowed = false;
                        }
                        if (!parameterNowAllowed){
                            allNowAllowed = false;
                        }
                    }
                }
            }
            if (filter.getGroupType() == PERMISSION_GROUP_TYPE_ALL_ALLOWED && anyChange && (allWereAllowed || allNowAllowed)){
                callListener(filter);
            } else if (filter.getGroupType() == PERMISSION_GROUP_TYPE_ANY && anyChange){
                callListener(filter);
            }
        }
    }

    /**
     * Determine if an individual RPC is allowed
     * @param rpcName
     * @param permissionItems
     * @param hmiLevel
     * @return boolean represents whether the RPC is allowed or not
     */
    private boolean isRPCAllowed(@NonNull FunctionID rpcName, Map<FunctionID, PermissionItem> permissionItems, HMILevel hmiLevel){
        PermissionItem permissionItem = permissionItems.get(rpcName);
        if (hmiLevel == null || permissionItem == null || permissionItem.getHMIPermissions() == null || permissionItem.getHMIPermissions().getAllowed() == null){
            return false;
        } else if (permissionItem.getHMIPermissions().getUserDisallowed() != null){
            return permissionItem.getHMIPermissions().getAllowed().contains(hmiLevel) && !permissionItem.getHMIPermissions().getUserDisallowed().contains(hmiLevel);
        } else {
            return permissionItem.getHMIPermissions().getAllowed().contains(hmiLevel);
        }
    }

    /**
     * Determine if an individual RPC is allowed for the current permission items and HMI level
     * @param rpcName
     * @return boolean represents whether the RPC is allowed or not
     */
    public boolean isRPCAllowed(@NonNull FunctionID rpcName){
        return isRPCAllowed(rpcName, currentPermissionItems, currentHMILevel);
    }

    /**
     * Determine if an individual permission parameter is allowed
     * @param rpcName
     * @param parameter
     * @param permissionItems
     * @param hmiLevel
     * @return boolean represents whether the permission parameter is allowed or not
     */
    private boolean isPermissionParameterAllowed(@NonNull FunctionID rpcName, @NonNull String parameter, Map<FunctionID, PermissionItem> permissionItems, HMILevel hmiLevel){
        PermissionItem permissionItem = permissionItems.get(rpcName);
        if (!isRPCAllowed(rpcName, permissionItems, hmiLevel) || permissionItem.getParameterPermissions() == null || permissionItem.getParameterPermissions().getAllowed() == null){
            return false;
        } else if (permissionItem.getParameterPermissions().getUserDisallowed() != null){
            return permissionItem.getParameterPermissions().getAllowed().contains(parameter) && !permissionItem.getParameterPermissions().getUserDisallowed().contains(parameter);
        } else {
            return permissionItem.getParameterPermissions().getAllowed().contains(parameter);
        }
    }

    /**
     * Determine if an individual permission parameter is allowed for current permission items and current HMI level
     * @param rpcName
     * @param parameter
     * @return boolean represents whether the permission parameter is allowed or not
     */
    public boolean isPermissionParameterAllowed(@NonNull FunctionID rpcName, @NonNull String parameter){
        return isPermissionParameterAllowed(rpcName, parameter, currentPermissionItems, currentHMILevel);
    }

    /**
     * Clean up everything after the manager is no longer needed
     */
    public void dispose(){
        super.dispose();

        // Remove onHMIStatusListener
        internalInterface.removeOnRPCNotificationListener(FunctionID.ON_HMI_STATUS, onHMIStatusListener);
        onHMIStatusListener = null;

        // Remove onPermissionsChangeListener
        internalInterface.removeOnRPCNotificationListener(FunctionID.ON_HMI_STATUS, onPermissionsChangeListener);
        onPermissionsChangeListener = null;

        // Remove developer's listeners
        filters.clear();

        transitionToState(SHUTDOWN);
    }

    /**
     * Determine if a group of permissions is allowed for the current HMI level
     * @param permissionElements
     * @return PermissionGroupStatus int value that gives an overall view whether the permissions are allowed or not
     * @see PermissionGroupStatus
     */
    public @PermissionGroupStatus int getGroupStatusOfPermissions(@NonNull List<PermissionElement> permissionElements){
        if (currentHMILevel == null){
            return PERMISSION_GROUP_STATUS_UNKNOWN;
        }

        boolean hasAllowed = false;
        boolean hasDisallowed = false;

        for (PermissionElement permissionElement : permissionElements) {
            // If at any point, we have both allowed and disallowed permissions, return the mixed result
            if (hasAllowed && hasDisallowed) {
                return PERMISSION_GROUP_STATUS_MIXED;
            }

            if (permissionElement == null){
                continue;
            } else if (!isRPCAllowed(permissionElement.getRpcName())){
                hasDisallowed = true;
            } else {
                if (permissionElement.getParameters() == null || permissionElement.getParameters().size() == 0){
                    hasAllowed = true;
                } else {
                    for (String permissionParameter : permissionElement.getParameters()) {
                        if (isPermissionParameterAllowed(permissionElement.getRpcName(), permissionParameter)) {
                            hasAllowed = true;
                        } else {
                            hasDisallowed = true;
                        }
                    }
                }
            }
        }

        if (!hasAllowed && !hasDisallowed){
            return PERMISSION_GROUP_STATUS_ALLOWED;
        } else if (hasAllowed && hasDisallowed) {
            return PERMISSION_GROUP_STATUS_MIXED;
        } else if (hasAllowed) {
            return PERMISSION_GROUP_STATUS_ALLOWED;
        } else{
            return PERMISSION_GROUP_STATUS_DISALLOWED;
        }
    }

    /**
     * Determine if a group of permissions is allowed for the current HMI level
     * This method is similar to getGroupStatusOfPermissions() but returns more detailed result about each individual permission
     * @param permissionElements
     * @return a map with keys that are the passed in RPC names specifying if that RPC and its parameter permissions are currently allowed for the current HMI level
     */
    public Map <FunctionID, PermissionStatus> getStatusOfPermissions(@NonNull List<PermissionElement> permissionElements){
        Map<FunctionID, PermissionStatus> statusOfPermissions = new HashMap<>();
        for (PermissionElement permissionElement : permissionElements) {
            if (permissionElement == null){
                continue;
            }
            Map<String, Boolean> allowedParameters = null;
            if (permissionElement.getParameters() != null && permissionElement.getParameters().size() > 0) {
                allowedParameters = new HashMap<>();
                for (String permissionParameter : permissionElement.getParameters()) {
                    allowedParameters.put(permissionParameter, isPermissionParameterAllowed(permissionElement.getRpcName(), permissionParameter));
                }
            }
            PermissionStatus permissionStatus = new PermissionStatus(permissionElement.getRpcName(), isRPCAllowed(permissionElement.getRpcName()), allowedParameters);
            statusOfPermissions.put(permissionElement.getRpcName(), permissionStatus);
        }
        return statusOfPermissions;
    }

    /**
     * Call the listener for a specific filter
     * @param filter
     */
    private void callListener(@NonNull PermissionFilter filter){
        int permissionGroupStatus = getGroupStatusOfPermissions(filter.getPermissionElements());
        Map <FunctionID, PermissionStatus> allowedPermissions = getStatusOfPermissions(filter.getPermissionElements());
        filter.getListener().onPermissionsChange(allowedPermissions, permissionGroupStatus);
    }

    /**
     * Add a listener to be called when there is permissions change
     * @param permissionElements
     * @param groupType PermissionGroupType int value represents whether we need the listener to be called when there is any permissions change or only when all permission become allowed
     * @param listener
     * @return unique uuid number for the listener. It can be used to remove the listener later.
     */
    public UUID addListener(@NonNull List<PermissionElement> permissionElements, @NonNull @PermissionGroupType int groupType, @NonNull OnPermissionChangeListener listener){
        PermissionFilter filter = new PermissionFilter(null, permissionElements, groupType, listener);
        filters.add(filter);
        return filter.getIdentifier();
    }

    /**
     * Removes specific listener
     * @param listenerId
     */
    public void removeListener(@NonNull UUID listenerId){
        for (PermissionFilter filter : filters) {
            if (filter.getIdentifier().equals(listenerId)) {
                filters.remove(filter);
                break;
            }
        }
    }

}