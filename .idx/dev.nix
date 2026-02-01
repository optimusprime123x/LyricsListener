# To learn more about how to use Nix to configure your environment
# see: https://firebase.google.com/docs/studio/customize-workspace
{ pkgs, ... }: {
  # Which nixpkgs channel to use.
  channel = "stable-24.05"; # or "unstable"
  # Use https://search.nixos.org/packages to find packages
  packages = [
    pkgs.jdk21_headless
    pkgs.unzip
    pkgs.python3
  ];
  # Sets environment variables in the workspace
  env = {
    GROQ_KEY = "abcd";
  };
  idx = {
    # Search for the extensions you want on https://open-vsx.org/ and use "publisher.id"
    extensions = [
      "Dart-Code.flutter"
      "Dart-Code.dart-code"
    ];
    workspace = {
      # Runs when a workspace is first created with this `dev.nix` file
      onCreate = { };
      # To run something each time the workspace is (re)started, use the `onStart` hook
      onStart = {
        start-server = "python3 -m http.server 8000 &";
        start-tunnel = "./cloudflared tunnel --url http://localhost:8000 2>&1 | tee cloudflared.log";
      };
    };
    # Enable previews and customize configuration
    previews = {
      enable = true;
      previews = {
        android = {
          command = ["flutter" "run" "--machine" "-d" "android" "-d" "localhost:5555" "--dart-define" "GROQ_KEY=$GROQ_KEY"];
          manager = "flutter";
        };
      };
    };
  };
}
