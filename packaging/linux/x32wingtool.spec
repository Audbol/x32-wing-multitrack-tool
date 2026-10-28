Summary: Multitrack tool for Behringer X32/M32 X-Live and WING-LIVE recordings
Name: x32wingtool
Version: APPLICATION_VERSION
Release: APPLICATION_RELEASE
License: MIT
Vendor: Audbol
URL: https://github.com/Audbol/x32-wing-multitrack-tool
Provides: x32wingtool

# jpackage disables automatic dependencies; declare what the bundled Java runtime needs, by
# library soname so the same package resolves on Fedora, RHEL/Alma/Rocky and openSUSE.
Autoprov: 0
Autoreq: 0
Requires: fontconfig, xdg-utils
Requires: libX11.so.6()(64bit), libXext.so.6()(64bit), libXi.so.6()(64bit), libXrender.so.1()(64bit)
Requires: libXtst.so.6()(64bit), libfreetype.so.6()(64bit), libasound.so.2()(64bit)

%define __jar_repack %{nil}
%define _build_id_links none

%define package_filelist %{_builddir}/%{name}.files
%define app_filelist %{_builddir}/%{name}.app.files
%define filesystem_filelist %{_builddir}/%{name}.filesystem.files

%define default_filesystem / /opt /usr /usr/bin /usr/lib /usr/local /usr/local/bin /usr/local/lib

%description
Turns Behringer X-Live and WING-LIVE SD card recordings into clean, named per-track FLAC or WAV
files: normalize, drop silent channels, split at markers, REAPER export, and card session tools.

%global __os_install_post %{nil}

%prep

%build

%install
rm -rf %{buildroot}
install -d -m 755 %{buildroot}/opt/x32wingtool
cp -r %{_sourcedir}/opt/x32wingtool/* %{buildroot}/opt/x32wingtool
(cd %{buildroot} && find . -type d -print) | sed -e 's/^\.//' -e '/^$/d' | sort > %{app_filelist}
{ rpm -ql filesystem || echo %{default_filesystem}; } | sort > %{filesystem_filelist}
comm -23 %{app_filelist} %{filesystem_filelist} > %{package_filelist}
sed -i -e 's/.*/%dir "&"/' %{package_filelist}
(cd %{buildroot} && find . -not -type d) | sed -e 's/^\.//' -e 's/.*/"&"/' >> %{package_filelist}

%files -f %{package_filelist}

%post
install -m 644 /opt/x32wingtool/lib/x32wingtool-X32WingTool.desktop /usr/share/applications/x32wingtool.desktop || :
update-desktop-database -q /usr/share/applications >/dev/null 2>&1 || :
ln -sf /opt/x32wingtool/bin/x32wing /usr/bin/x32wing || :

%preun
# $1 = 0 on erase, 1 on upgrade (the new package's %post re-creates everything).
if [ "$1" = 0 ]; then
  rm -f /usr/share/applications/x32wingtool.desktop
  [ -L /usr/bin/x32wing ] && rm -f /usr/bin/x32wing
  update-desktop-database -q /usr/share/applications >/dev/null 2>&1 || :
fi
exit 0

%clean
