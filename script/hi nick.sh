# Example command
# Must cd into the repo root.
# "./script/hi nick.sh" -s <sdk cmdline tools dir>
# bash ./script/hi\ nick.sh -s C:/Users/lemee/AppData/Local/Android/Sdk/cmdline-tools/latest/bin -p windows

SDCARD_SIZE=1G
AVD_ARCH=x86_64
AVD_FLAVOUR=default
AVD_HW_PROFILE=iphone_9_r1 # specify your own hw profile



EXTENSION=""
createAvd() {
    IMAGE="system-images;android-$2;$AVD_FLAVOUR;$AVD_ARCH"
    NAME=sdk$2_ms_compat_test

    $1/sdkmanager$EXTENSION --install $IMAGE
    $1/avdmanager$EXTENSION --silent create avd -n $NAME -k $IMAGE -d $AVD_HW_PROFILE -c $SDCARD_SIZE -p ./scripts/avd/$NAME
}

deleteAvd() {
    NAME=sdk$2_ms_compat_test

    $1/avdmanager$EXTENSION --silent delete avd -n $NAME
}

runUnitTests() {
    NAME=sdk$2_ms_compat_test
    if [ $OS == "windows" ]; then
          EXTENSION2=".exe"
    fi
    $1/../../../emulator/emulator$EXTENSION2 -avd $NAME & $1/../../../platform-tools/adb$EXTENSION2 wait-for-device
    $1/../../../platform-tools/adb$EXTENSION2 shell input keyevent 82
    ./gradlew connectedDebugAndroidTest
    $1/../../../platform-tools/adb$EXTENSION2 emu kill
}





OS="unix" #unix | windows
CLEAN=0 # to clean install avds
while [[ $# -gt 0 ]]; do
    case $1 in
        -s|--sdktools)
            SDK_TOOLS="$2"
            shift
            shift
            ;;
        -p|--platform)
            OS="$2"
            shift
            shift
            ;;
        --clean)
            CLEAN=1
            shift
            ;;
        --clean-only)
            CLEAN=2
            shift
            ;;
        -h|--help)
            echo
            shift
            ;;
        -*|--*)
            echo "Unknown option $1"
            exit 1
            ;;
        *)
            POSITIONAL_ARGS+=("$1")
            shift
            ;;
    esac
done


if [ $OS == "windows" ]; then
      EXTENSION=".bat"
fi

# create avds
sdk_ints=()
for i in {21..30}; do
    echo $i
    sdk_ints+=("$i")
    if [ $CLEAN == 1 ]; then
        echo Removing avd if exists for SDK: $i
        deleteAvd $SDK_TOOLS $i
    fi

    if [ $CLEAN == 2 ]; then
        echo Removing avd if exists for SDK: $i
        deleteAvd $SDK_TOOLS $i
    else
        echo Setting up avd for SDK: $i
        createAvd $SDK_TOOLS $i
    fi
done

if [ $CLEAN == 2 ]; then
    exit 0
fi

echo ------------------------
echo ""
echo ""
echo ""
echo ""
echo ""
echo ------------------------
# run unit tests
for i in "${sdk_ints[@]}"; do
    echo Running unit test for SDK: $i
    runUnitTests $SDK_TOOLS $i
done


#createAvd $SDK_TOOLS 30
#startAvd $SDK_TOOLS 30
