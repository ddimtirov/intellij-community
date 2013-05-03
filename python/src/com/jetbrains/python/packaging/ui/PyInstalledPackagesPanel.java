package com.jetbrains.python.packaging.ui;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.util.Consumer;
import com.intellij.webcore.packaging.PackagesNotificationPanel;
import com.jetbrains.python.packaging.PyExternalProcessException;
import com.jetbrains.python.packaging.PyPackage;
import com.jetbrains.python.packaging.PyPackageManager;
import com.jetbrains.python.packaging.PyPackageManagerImpl;
import com.jetbrains.python.sdk.PythonSdkType;
import com.jetbrains.python.sdk.flavors.IronPythonSdkFlavor;
import com.jetbrains.python.sdk.flavors.PythonSdkFlavor;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * @author yole
 */
public class PyInstalledPackagesPanel extends InstalledPackagesPanel {
  public static final String INSTALL_DISTRIBUTE = "installDistribute";
  public static final String INSTALL_PIP = "installPip";
  public static final String CREATE_VENV = "createVEnv";
  private boolean myHasDistribute;
  private boolean myHasPip = true;

  public PyInstalledPackagesPanel(Project project, PackagesNotificationPanel area) {
    super(project, area);

    myNotificationArea.addLinkHandler(INSTALL_DISTRIBUTE, new Runnable() {
      @Override
      public void run() {
        final Sdk sdk = mySelectedSdk;
        if (sdk != null) {
          installManagementTool(sdk, PyPackageManagerImpl.DISTRIBUTE);
        }
      }
    });
    myNotificationArea.addLinkHandler(INSTALL_PIP, new Runnable() {
      @Override
      public void run() {
        final Sdk sdk = mySelectedSdk;
        if (sdk != null) {
          installManagementTool(sdk, PyPackageManagerImpl.PIP);
        }
      }
    });
  }

  public void updateNotifications(@NotNull final Sdk selectedSdk) {
    final Application application = ApplicationManager.getApplication();
    application.executeOnPooledThread(new Runnable() {
      @Override
      public void run() {
        PyExternalProcessException exc = null;
        try {
          PyPackageManagerImpl packageManager = (PyPackageManagerImpl)PyPackageManager.getInstance(selectedSdk);
          myHasDistribute = packageManager.findPackage(PyPackageManagerImpl.PACKAGE_DISTRIBUTE) != null;
          if (!myHasDistribute) {
            myHasDistribute = packageManager.findPackage(PyPackageManagerImpl.PACKAGE_SETUPTOOLS) != null;
          }
          myHasPip = packageManager.findPackage(PyPackageManagerImpl.PACKAGE_PIP) != null;
        }
        catch (PyExternalProcessException e) {
          exc = e;
        }
        final PyExternalProcessException externalProcessException = exc;
        application.invokeLater(new Runnable() {
          @Override
          public void run() {
            if (selectedSdk == mySelectedSdk) {
              final PythonSdkFlavor flavor = PythonSdkFlavor.getFlavor(selectedSdk);
              final boolean invalid = PythonSdkType.isInvalid(selectedSdk);
              boolean allowCreateVirtualEnv =
                !(PythonSdkType.isRemote(selectedSdk) || flavor instanceof IronPythonSdkFlavor) &&
                !PythonSdkType.isVirtualEnv(selectedSdk) &&
                myNotificationArea.hasLinkHandler(CREATE_VENV);
              final String createVirtualEnvLink = "<a href=\"" + CREATE_VENV + "\">create new VirtualEnv</a>";
              myNotificationArea.hide();
              if (!invalid) {
                String text = null;
                if (externalProcessException != null) {
                  final int retCode = externalProcessException.getRetcode();
                  if (retCode == PyPackageManagerImpl.ERROR_NO_PIP) {
                    myHasPip = false;
                  }
                  else if (retCode == PyPackageManagerImpl.ERROR_NO_DISTRIBUTE) {
                    myHasDistribute = false;
                  }
                  else {
                    text = externalProcessException.getMessage();
                  }
                  final boolean hasPackagingTools = myHasPip && myHasDistribute;
                  allowCreateVirtualEnv &= !hasPackagingTools;
                }
                if (text == null) {
                  if (!myHasDistribute) {
                    text = "Python package management tools not found. <a href=\"" + INSTALL_DISTRIBUTE + "\">Install 'distribute'</a>";
                  }
                  else if (!myHasPip) {
                    text = "Python packaging tool 'pip' not found. <a href=\"" + INSTALL_PIP + "\">Install 'pip'</a>";
                  }
                }
                if (text != null) {
                  if (allowCreateVirtualEnv) {
                    text += " or " + createVirtualEnvLink;
                  }
                  myNotificationArea.showWarning(text);
                }
              }

              myInstallButton.setEnabled(!invalid && externalProcessException == null && myHasPip);
            }
          }
        }, ModalityState.any());
      }
    });
  }

  private void installManagementTool(@NotNull final Sdk sdk, final String name) {
    final PyPackageManagerImpl.UI ui = new PyPackageManagerImpl.UI(myProject, sdk, new PyPackageManagerImpl.UI.Listener() {
      @Override
      public void started() {
        myPackagesTable.setPaintBusy(true);
      }

      @Override
      public void finished(List<PyExternalProcessException> exceptions) {
        myPackagesTable.setPaintBusy(false);
        PyPackageManagerImpl packageManager = (PyPackageManagerImpl)PyPackageManager.getInstance(sdk);
        if (!exceptions.isEmpty()) {
          final String firstLine = "Install package failed. ";
          final String description = PyPackageManagerImpl.UI.createDescription(exceptions, firstLine);
          packageManager.showInstallationError(myProject, "Failed to install " + name, description);
        }
        packageManager.refresh();
        updatePackages(sdk);
        for (Consumer<Sdk> listener : myPathChangedListeners) {
          listener.consume(sdk);
        }
        updateNotifications(sdk);
      }
    });
    ui.installManagement(name);
  }

  @Override
  protected boolean canUninstallPackage(PyPackage pyPackage) {
    if (!myHasPip) return false;
    if (PythonSdkType.isVirtualEnv(mySelectedSdk)) {
      final String location = pyPackage.getLocation();
      if (location != null && location.startsWith(PyPackageManagerImpl.getUserSite())) {
        return false;
      }
    }
    if ("pip".equals(pyPackage.getName()) || "distribute".equals(pyPackage.getName())) {
      return false;
    }
    return true;
  }

  @Override
  protected boolean canUpgradePackage(PyPackage pyPackage) {
    return myHasPip;
  }
}
