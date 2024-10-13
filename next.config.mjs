/** @type {import('next').NextConfig} */
const nextConfig = {
    // Your existing Next.js configurations can go here
  
    webpack: (config, { isServer }) => {
      if (!isServer) {
        // Avoid module not found error for 'fs' in client-side code
        config.resolve.fallback = {
          ...config.resolve.fallback,
          fs: false,
        };
      }
      return config;
    },
  };
  
  export default nextConfig;
  