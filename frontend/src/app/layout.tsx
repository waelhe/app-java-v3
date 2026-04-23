/**
 * Root Layout - Main application wrapper
 * @version 2.0
 */
import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import "./globals.css";
import { Toaster } from "@/components/ui/toaster";
import { LanguageProvider, AuthProvider, CartProvider, RegionProvider } from "@/contexts";
import { Header, Footer, BottomNav } from "@/components/layout";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "نبض الضاحية وقدسيا - خدمات منطقتك في مكان واحد",
  description: "منصة متكاملة لخدمات الضاحية وقدسيا - صيدليات مناوبة، أطباء، سوق محلي، عقارات، ومجتمع. كل ما تحتاجه في مكان واحد.",
  keywords: ["قدسيا", "نبض", "خدمات محلية", "صيدليات", "أطباء", "سوق", "عقارات", "مجتمع", "دمشق", "سوريا", "ضاحية قدسيا"],
  authors: [{ name: "Nabd Dahia & Qudsaya Team" }],
  icons: {
    icon: "https://z-cdn.chatglm.cn/z-ai/static/logo.svg",
  },
  openGraph: {
    title: "نبض الضاحية وقدسيا - خدمات منطقتك",
    description: "منصة متكاملة لخدمات الضاحية وقدسيا - كل ما تحتاجه في مكان واحد",
    type: "website",
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="ar" dir="rtl" suppressHydrationWarning>
      <body
        className={`${geistSans.variable} ${geistMono.variable} antialiased bg-background text-foreground`}
      >
        <LanguageProvider>
          <AuthProvider>
            <CartProvider>
              <RegionProvider>
                <div className="min-h-screen flex flex-col">
                  <Header />
                  <div className="flex-1 pb-20 md:pb-0">
                    {children}
                  </div>
                  <Footer />
                  <BottomNav />
                </div>
                <Toaster />
              </RegionProvider>
            </CartProvider>
          </AuthProvider>
        </LanguageProvider>
      </body>
    </html>
  );
}
