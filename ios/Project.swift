import ProjectDescription

let project = Project(
    name: "DaidaiPanel",
    organizationName: "Daidai",
    options: .options(
        automaticSchemesOptions: .enabled(
            targetSchemesGrouping: .singleScheme,
            codeCoverageEnabled: false,
            testingOptions: []
        )
    ),
    packages: [],
    settings: .settings(
        base: [
            "IPHONEOS_DEPLOYMENT_TARGET": "16.0",
            "SWIFT_VERSION": "5.9",
            "CODE_SIGN_IDENTITY": "-",
            "CODE_SIGNING_REQUIRED": "NO",
            "CODE_SIGNING_ALLOWED": "NO"
        ]
    ),
    targets: [
        Target(
            name: "DaidaiPanel",
            platform: .iOS,
            product: .app,
            bundleId: "com.daidai.panel",
            deploymentTarget: .iOS(targetVersion: "16.0", devices: [.iphone, .ipad]),
            infoPlist: "DaidaiPanel/Info.plist",
            sources: ["DaidaiPanel/**"],
            resources: [
                "DaidaiPanel/Resources/**",
                "DaidaiPanel/LaunchScreen.storyboard"
            ],
            dependencies: [
                // gomobile生成的框架
            ],
            settings: .settings(
                base: [
                    "PRODUCT_BUNDLE_IDENTIFIER": "com.daidai.panel",
                    "INFOPLIST_FILE": "DaidaiPanel/Info.plist",
                    "CODE_SIGN_STYLE": "Automatic",
                    "DEVELOPMENT_TEAM": ""
                ]
            )
        )
    ]
)
