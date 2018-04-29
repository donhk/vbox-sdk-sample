package ninja.donhk;

import org.virtualbox_5_2.*;

import java.util.ArrayList;
import java.util.List;

/**
 * @author donhk
 */
public class VBoxManager {

    private final VirtualBoxManager boxManager;
    private final IVirtualBox vbox;
    private IProgress progress;

    public VBoxManager() {
        boxManager = VirtualBoxManager.createInstance(null);
        boxManager.connect("http://192.168.15.11:18083", null, null);
        vbox = boxManager.getVBox();
    }

    public String getVBoxVersion() {
        return vbox.getVersion();
    }

    public List<IMachine> getMachines(MachineState state) {
        List<IMachine> iMachines = new ArrayList<>();
        for (IMachine machine : vbox.getMachines()) {
            if (machine.getState() == state) {
                iMachines.add(machine);
            }
        }
        return iMachines;
    }

    public boolean cloneMachineFromSeed(String seedName, String snapshot, String machineName) {
        if (!machineExists(seedName)) {
            return false;
        }
        IMachine seedMachine = vbox.findMachine(seedName);
        if (!snapshotExists(seedMachine, snapshot)) {
            return false;
        }
        ISnapshot iSnapshot = seedMachine.findSnapshot(snapshot);

        IMachine sourceMachine = iSnapshot.getMachine();
        //create a new empty machine container
        IMachine newMachine = boxManager.getVBox().createMachine(
                null/*settingsFile*/,
                machineName/*name*/,
                null/*groups[]*/,
                seedMachine.getOSTypeId()/*osTypeId*/,
                "forceOverwrite=1"/*flags*/
        );
        //prepare setting to clone this machine
        List<CloneOptions> options = new ArrayList<>();
        //we want it to be a clone to save space
        options.add(CloneOptions.Link);
        //start cloning
        IProgress progress = sourceMachine.cloneTo(newMachine, CloneMode.MachineState, options);
        wait(progress);
        //save changes and register
        newMachine.saveSettings();
        vbox.registerMachine(newMachine);
        return true;
    }

    public boolean addSharedDirectory(String machineName, String dirName, String hostPath) {
        if (!machineExists(machineName)) {
            return false;
        }
        IMachine machine = vbox.findMachine(machineName);
        ISession session = boxManager.getSessionObject();
        machine.lockMachine(session, LockType.Write);
        try {
            session.getMachine().createSharedFolder(dirName, hostPath, true, true);
            session.getMachine().saveSettings();
        } finally {
            waitToUnlock(session, machine);
        }
        return true;
    }

    public boolean launchMachine(String machineName, LaunchMode mode) {
        if (!machineExists(machineName)) {
            return false;
        }
        IMachine machine = vbox.findMachine(machineName);
        ISession session = boxManager.getSessionObject();
        try {
            IProgress progress = machine.launchVMProcess(session, mode.name(), null);
            wait(progress);
        } finally {
            session.unlockMachine();
        }
        //TODO add a timeout
        try {
            String ipv4 = null;
            do {
                Thread.sleep(3000);
                ipv4 = getMachineIPv4(machineName);
            } while (ipv4 == null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return true;
    }

    public boolean addPortForwardRule(String networkName, int hostPort, int guestPort, String machineName) {
        String ipv4 = getMachineIPv4(machineName);
        if (ipv4 == null) {
            return false;
        }
        if (!natNetworkExists(networkName)) {
            return false;
        }
        INATNetwork natNet = vbox.findNATNetworkByName(networkName);
        natNet.addPortForwardRule(
                /*isIpv6*/false,
                /*ruleName*/"ssh" + hostPort,
                /*proto*/NATProtocol.TCP,
                /*hostIP*/"0.0.0.0",
                /*hostPort*/hostPort,
                /*guestIP*/ipv4,
                /*guestPort*/guestPort
        );

        return true;
    }

    public void rmPortForwardRule(String networkName, int hostPort) {
        if (!natNetworkExists(networkName)) {
            return;
        }
        INATNetwork natNet = vbox.findNATNetworkByName(networkName);
        String ruleName = "ssh" + hostPort;
        //make sure the rule exists before attempt to drop it
        for (String rule : natNet.getPortForwardRules4()) {
            if (rule.startsWith(ruleName)) {
                natNet.removePortForwardRule(false, ruleName);
            }
        }
    }

    public String getMachineIPv4(String machineName) {
        if (!machineExists(machineName)) {
            return null;
        }
        IMachine machine = vbox.findMachine(machineName);

        //scan the machine properties looking for its ip, once
        //we get it, we can assemble the command to add the new rule
        Holder<List<String>> keys = new Holder<>();
        Holder<List<String>> values = new Holder<>();
        Holder<List<Long>> timestamps = new Holder<>();
        Holder<List<String>> flags = new Holder<>();
        machine.enumerateGuestProperties(null, keys, values, timestamps, flags);
        String ipv4 = null;
        for (int i = 0; i < keys.value.size(); i++) {
            String key = keys.value.get(i);
            String val = values.value.get(i);
            if (key.contains("GuestInfo/Net/0/V4/IP") && val.startsWith("10.0")) {
                ipv4 = val;
                break;
            }
        }
        //if this property was not found, we can't continue
        return ipv4;
    }

    public synchronized void cleanUpVM(String machineName) {
        if (!machineExists(machineName)) {
            return;
        }
        IMachine machine = vbox.findMachine(machineName);
        MachineState state = machine.getState();
        ISession session = boxManager.getSessionObject();
        machine.lockMachine(session, LockType.Shared);
        try {
            if (state.value() >= MachineState.FirstOnline.value() && state.value() <= MachineState.LastOnline.value()) {
                IProgress progress = session.getConsole().powerDown();
                wait(progress);
            }
        } finally {
            waitToUnlock(session, machine);
            System.err.println("Deleting machine " + machineName);
            List<IMedium> media = machine.unregister(CleanupMode.DetachAllReturnHardDisksOnly);
            machine.deleteConfig(media);
        }
    }

    public IProgress getProgress() {
        return progress;
    }

    /**
     * +---------[powerDown()] <- Stuck <--[failure]-+
     * V                                             |
     * +-> PoweredOff --+-->[powerUp()]--> Starting --+      | +-----[resume()]-----+
     * |                |                             |      | V                    |
     * |   Aborted -----+                             +--> Running --[pause()]--> Paused
     * |                                              |      ^ |                   ^ |
     * |   Saved -----------[powerUp()]--> Restoring -+      | |                   | |
     * |     ^                                               | |                   | |
     * |     |     +-----------------------------------------+-|-------------------+ +
     * |     |     |                                           |                     |
     * |     |     +- OnlineSnapshotting <--[takeSnapshot()]<--+---------------------+
     * |     |                                                 |                     |
     * |     +-------- Saving <--------[saveState()]<----------+---------------------+
     * |                                                       |                     |
     * +-------------- Stopping -------[powerDown()]<----------+---------------------+
     *
     * @param machineName target machine
     */
    private void shutdownMachine(String machineName) {
        if (!machineExists(machineName)) {
            return;
        }
        IMachine machine = vbox.findMachine(machineName);
        MachineState state = machine.getState();
        ISession session = boxManager.getSessionObject();
        machine.lockMachine(session, LockType.Shared);
        try {
            if (state.value() >= MachineState.FirstOnline.value() && state.value() <= MachineState.LastOnline.value()) {
                IProgress progress = session.getConsole().powerDown();
                wait(progress);
            }
        } finally {
            waitToUnlock(session, machine);
        }
    }

    /**
     * Wait untill the current session is unlocked
     *
     * @param session session
     * @param machine machine
     */
    private void waitToUnlock(ISession session, IMachine machine) {
        session.unlockMachine();
        SessionState sessionState = machine.getSessionState();
        while (!SessionState.Unlocked.equals(sessionState)) {
            sessionState = machine.getSessionState();
            try {
                System.err.println("Waiting for session unlock...[" + sessionState.name() + "][" + machine.getName() + "]");
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                System.err.println("Interrupted while waiting for session to be unlocked");
            }
        }
    }

    /**
     * Waits until a tasks finished
     * <p>
     * Progress object to track the operation completion. Expected result codes:
     * E_UNEXPECTED	Virtual machine not registered.
     * E_INVALIDARG	Invalid session type type.
     * VBOX_E_OBJECT_NOT_FOUND	No machine matching machineId found.
     * VBOX_E_INVALID_OBJECT_STATE	Session already open or being opened.
     * VBOX_E_IPRT_ERROR	Launching process for machine failed.
     * VBOX_E_VM_ERROR	Failed to assign machine to session.
     *
     * @param progress current task monitor
     */
    private void wait(IProgress progress) {
        //make this available for the caller
        this.progress = progress;
        progress.waitForCompletion(-1);
        if (progress.getResultCode() != 0) {
            System.err.println("Operation failed: " + progress.getErrorInfo().getText());
        }
    }

    private boolean machineExists(String machineName) {
        ///VBOX_E_OBJECT_NOT_FOUND
        //kind of "exists"
        if (machineName == null) {
            return false;
        }
        //since the method findMachine returns org.virtualbox_5_2.VBoxExceptio
        //if the machine doesn't exists we will need to find it by
        //ourselves iterating over all the machines
        List<IMachine> machines = vbox.getMachines();
        for (IMachine machine : machines) {
            if (machine.getName().equals(machineName)) {
                return true;
            }
        }
        return false;
    }

    private boolean snapshotExists(IMachine machine, String snapshot) {
        ///VBOX_E_OBJECT_NOT_FOUND
        return machine.findSnapshot(snapshot) != null;
    }

    private boolean natNetworkExists(String networkName) {
        if (networkName == null) {
            return false;
        }
        for (INATNetwork network : vbox.getNATNetworks()) {
            if (network.getNetworkName().equals(networkName)) {
                return true;
            }
        }
        return false;
    }
}
