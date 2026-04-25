/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Bottom Navigation Component - Airbnb Style (Optimized - No Framer Motion)
 * 
 * شريط التنقل السفلي للموبايل - نبض قدسيا
 */

'use client';

import React, { useState, useEffect, useRef } from 'react';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { 
  Home, 
  Search, 
  Heart, 
  ShoppingBag,
  Phone
} from 'lucide-react';
import { useLanguage } from '@/contexts/LanguageContext';

interface NavItem {
  id: string;
  name: string;
  nameEn: string;
  path: string;
  icon: React.ElementType;
  isSpecial?: boolean;
  isTel?: boolean;
  badge?: number;
}

export default function BottomNav() {
  const pathname = usePathname();
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const [activeId, setActiveId] = useState('home');
  const [isScrollingDown, setIsScrollingDown] = useState(false);
  const lastScrollYRef = useRef(0);

  // تتبع اتجاه التمرير
  useEffect(() => {
    const handleScroll = () => {
      const currentScrollY = window.scrollY;
      if (currentScrollY > lastScrollYRef.current && currentScrollY > 80) {
        setIsScrollingDown(true);
      } else {
        setIsScrollingDown(false);
      }
      lastScrollYRef.current = currentScrollY;
    };
    window.addEventListener('scroll', handleScroll, { passive: true });
    return () => window.removeEventListener('scroll', handleScroll);
  }, []);

  const navItems: NavItem[] = [
    {
      id: 'home',
      name: 'الرئيسية',
      nameEn: 'Home',
      path: '/',
      icon: Home,
    },
    {
      id: 'explore',
      name: 'استكشف',
      nameEn: 'Explore',
      path: '/#explore',
      icon: Search,
    },
    {
      id: 'market',
      name: 'السوق',
      nameEn: 'Market',
      path: '/market',
      icon: ShoppingBag,
    },
    {
      id: 'favorites',
      name: 'المفضلة',
      nameEn: 'Favorites',
      path: '/#favorites',
      icon: Heart,
    },
    {
      id: 'emergency',
      name: 'طوارئ',
      nameEn: 'Emergency',
      path: 'tel:112',
      icon: Phone,
      isTel: true,
      isSpecial: true,
    },
  ];

  const handleClick = (id: string) => {
    setActiveId(id);
  };

  return (
    <div className={`fixed bottom-0 left-0 right-0 z-50 md:hidden transition-all duration-300 ${isScrollingDown ? 'translate-y-full opacity-0' : 'translate-y-0 opacity-100'}`}>
      {/* Airplane-style background with blur */}
      <div className="bg-white/95 backdrop-blur-xl border-t border-gray-200/80 shadow-[0_-4px_20px_rgba(0,0,0,0.08)]">
        {/* Main Navigation */}
        <div className="flex justify-around items-center h-16 px-2 safe-area-inset-bottom">
          {navItems.map((item) => {
            const Icon = item.icon;
            const isActive = pathname === item.path || activeId === item.id;
            
            if (item.isTel) {
              return (
                <a
                  key={item.id}
                  href={item.path}
                  onClick={() => handleClick(item.id)}
                  className="relative flex flex-col items-center justify-center min-w-[64px] h-full group"
                >
                  {/* Special Emergency Button */}
                  <div className="relative flex items-center justify-center group-active:scale-95 transition-transform">
                    <div className="absolute inset-0 bg-gradient-to-br from-red-400 to-red-600 rounded-full blur-sm opacity-50 group-hover:opacity-70 transition-opacity" />
                    <div className="relative flex items-center justify-center w-11 h-11 bg-gradient-to-br from-red-500 to-red-600 rounded-full shadow-lg shadow-red-500/30">
                      <Icon className="w-5 h-5 text-white" strokeWidth={2.5} />
                    </div>
                  </div>
                  <span className="mt-1 text-[10px] font-semibold text-red-600">
                    {isArabic ? item.name : item.nameEn}
                  </span>
                </a>
              );
            }

            return (
              <Link
                key={item.id}
                href={item.path}
                onClick={() => handleClick(item.id)}
                className="relative flex flex-col items-center justify-center min-w-[64px] h-full group"
              >
                {/* Active indicator - floating pill */}
                {isActive && (
                  <div className="absolute -top-1 w-10 h-1.5 bg-gradient-to-r from-emerald-500 to-teal-500 rounded-full transition-all duration-300" />
                )}

                {/* Icon with container */}
                <div
                  className={`
                    relative flex items-center justify-center w-10 h-10 rounded-2xl transition-all duration-300
                    ${isActive 
                      ? 'bg-gradient-to-br from-emerald-50 to-teal-50 shadow-sm' 
                      : 'group-hover:bg-gray-100'
                    }
                    group-active:scale-90
                  `}
                >
                  <Icon 
                    className={`
                      w-5 h-5 transition-all duration-300
                      ${isActive 
                        ? 'text-emerald-600 stroke-[2.5]' 
                        : 'text-gray-500 group-hover:text-gray-700 stroke-[2]'
                      }
                    `} 
                  />
                  
                  {/* Badge for notifications */}
                  {item.badge && (
                    <span className="absolute -top-0.5 -right-0.5 flex items-center justify-center min-w-[18px] h-[18px] px-1 bg-gradient-to-r from-rose-500 to-pink-500 text-white text-[10px] font-bold rounded-full shadow-sm">
                      {item.badge}
                    </span>
                  )}
                </div>

                {/* Label */}
                <span 
                  className={`
                    mt-0.5 text-[10px] font-semibold transition-colors duration-300
                    ${isActive 
                      ? 'text-emerald-600' 
                      : 'text-gray-500 group-hover:text-gray-700'
                    }
                  `}
                >
                  {isArabic ? item.name : item.nameEn}
                </span>
              </Link>
            );
          })}
        </div>

        {/* iOS Safe Area */}
        <div className="h-[env(safe-area-inset-bottom)]" />
      </div>
    </div>
  );
}
