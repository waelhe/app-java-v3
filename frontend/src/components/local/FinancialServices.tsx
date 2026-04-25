'use client';

import React, { useState, useRef, useEffect } from 'react';
import { 
  Banknote, CreditCard, Landmark, Building2, Wallet,
  Star, Heart, ChevronLeft, ChevronRight, ArrowRightLeft, Grid3X3
} from 'lucide-react';
import { useLanguage } from '@/contexts/LanguageContext';
import { useRegion, Region } from '@/contexts/RegionContext';
import { Drawer, DrawerContent, DrawerHeader, DrawerTitle } from '@/components/ui/drawer';

interface FinancialService {
  id: string;
  name: string;
  nameEn: string;
  type: string;
  typeEn: string;
  category: 'exchange' | 'transfer' | 'bank' | 'bills' | 'ewallet';
  image: string;
  location: string;
  phone: string;
  services: string[];
  servicesEn: string[];
  rating: number;
  reviews: number;
  featured?: boolean;
  new?: boolean;
}

const qudsayaCenterFinancial: FinancialService[] = [
  { id: 'ex1', name: 'صرافة الأمانة', nameEn: 'Al-Amana Exchange', type: 'صرافة', typeEn: 'Exchange', category: 'exchange', image: 'https://images.unsplash.com/photo-1580519542036-c47de6196ba5?auto=format&fit=crop&w=300&q=80', location: 'قدسيا - الساحة الرئيسية', phone: '0999111101', services: ['دولار', 'يورو', 'عملات عربية'], servicesEn: ['USD', 'EUR', 'Arab Currencies'], rating: 4.9, reviews: 220, featured: true },
  { id: 'ex2', name: 'صرافة النور', nameEn: 'Al-Noor Exchange', type: 'صرافة', typeEn: 'Exchange', category: 'exchange', image: 'https://images.unsplash.com/photo-1526304640581-d334cdbbf45e?auto=format&fit=crop&w=300&q=80', location: 'قدسيا - شارع الرئيسي', phone: '0999111102', services: ['تبديل عملات', 'أسعار منافسة'], servicesEn: ['Currency Exchange', 'Competitive Rates'], rating: 4.7, reviews: 165, new: true },
  { id: 'tr1', name: 'مركز الحوالات السريعة', nameEn: 'Quick Transfer Center', type: 'حوالات مالية', typeEn: 'Money Transfer', category: 'transfer', image: 'https://images.unsplash.com/photo-1556742049-0cfed4f6a45d?auto=format&fit=crop&w=300&q=80', location: 'قدسيا - الساحة', phone: '0999222201', services: ['ويسترن يونيون', 'مونيغرام', 'تحويلات محلية'], servicesEn: ['Western Union', 'MoneyGram', 'Local Transfers'], rating: 4.8, reviews: 185, featured: true },
  { id: 'bk1', name: 'فرع البنك التجاري السوري', nameEn: 'Syrian Commercial Bank', type: 'بنك', typeEn: 'Bank', category: 'bank', image: 'https://images.unsplash.com/photo-1544876459-7d3a9a43d4e7?auto=format&fit=crop&w=300&q=80', location: 'قدسيا - الساحة', phone: '0116661111', services: ['حسابات', 'قروض', 'ودائع'], servicesEn: ['Accounts', 'Loans', 'Deposits'], rating: 4.2, reviews: 145 },
  { id: 'bl1', name: 'مركز دفع الفواتير', nameEn: 'Bills Payment Center', type: 'دفع فواتير', typeEn: 'Bills Payment', category: 'bills', image: 'https://images.unsplash.com/photo-1554224155-6726b3ff858f?auto=format&fit=crop&w=300&q=80', location: 'قدسيا - الساحة', phone: '0999333301', services: ['كهرباء', 'مياه', 'هاتف', 'إنترنت'], servicesEn: ['Electricity', 'Water', 'Phone', 'Internet'], rating: 4.6, reviews: 125, featured: true },
  { id: 'ew1', name: 'وكيل سيرياتيل كاش', nameEn: 'Syriatel Cash Agent', type: 'محفظة إلكترونية', typeEn: 'E-Wallet', category: 'ewallet', image: 'https://images.unsplash.com/photo-1563013544-824ae1b704d3?auto=format&fit=crop&w=300&q=80', location: 'قدسيا - الساحة', phone: '0999444401', services: ['إيداع', 'سحب', 'تحويل'], servicesEn: ['Deposit', 'Withdraw', 'Transfer'], rating: 4.7, reviews: 180, featured: true },
];

const qudsayaDahiaFinancial: FinancialService[] = [
  { id: 'dex1', name: 'صرافة الضاحية', nameEn: 'Dahia Exchange', type: 'صرافة', typeEn: 'Exchange', category: 'exchange', image: 'https://images.unsplash.com/photo-1580519542036-c47de6196ba5?auto=format&fit=crop&w=300&q=80', location: 'الضاحية - الساحة', phone: '0999111104', services: ['تبديل عملات', 'دولار', 'يورو'], servicesEn: ['Currency Exchange', 'USD', 'EUR'], rating: 4.8, reviews: 195, featured: true },
  { id: 'dtr1', name: 'مركز الحوالات - الضاحية', nameEn: 'Dahia Transfer Center', type: 'حوالات مالية', typeEn: 'Money Transfer', category: 'transfer', image: 'https://images.unsplash.com/photo-1556742049-0cfed4f6a45d?auto=format&fit=crop&w=300&q=80', location: 'الضاحية - الساحة', phone: '0999222203', services: ['تحويلات', 'ويسترن يونيون'], servicesEn: ['Transfers', 'Western Union'], rating: 4.7, reviews: 142, featured: true },
  { id: 'dbl1', name: 'مركز الدفع - الضاحية', nameEn: 'Dahia Payment Center', type: 'دفع فواتير', typeEn: 'Bills Payment', category: 'bills', image: 'https://images.unsplash.com/photo-1554224155-6726b3ff858f?auto=format&fit=crop&w=300&q=80', location: 'الضاحية - الساحة', phone: '0999333303', services: ['فواتير', 'رسوم', 'دفع إلكتروني'], servicesEn: ['Bills', 'Fees', 'E-Payment'], rating: 4.5, reviews: 108 },
  { id: 'dew1', name: 'وكيل سيرياتيل - الضاحية', nameEn: 'Syriatel Cash - Dahia', type: 'محفظة إلكترونية', typeEn: 'E-Wallet', category: 'ewallet', image: 'https://images.unsplash.com/photo-1563013544-824ae1b704d3?auto=format&fit=crop&w=300&q=80', location: 'الضاحية - المركز التجاري', phone: '0999444403', services: ['إيداع', 'سحب', 'تحويل'], servicesEn: ['Deposit', 'Withdraw', 'Transfer'], rating: 4.6, reviews: 165, new: true },
];

const dataByRegion: Record<Region, FinancialService[]> = {
  'qudsaya-center': qudsayaCenterFinancial,
  'qudsaya-dahia': qudsayaDahiaFinancial
};

const categoryFilters = [
  { id: 'all', name: 'الكل', nameEn: 'All', icon: Banknote },
  { id: 'exchange', name: 'صرافين', nameEn: 'Exchange', icon: Banknote },
  { id: 'transfer', name: 'حوالات', nameEn: 'Transfers', icon: ArrowRightLeft },
  { id: 'bank', name: 'بنوك', nameEn: 'Banks', icon: Landmark },
  { id: 'bills', name: 'دفع فواتير', nameEn: 'Bills', icon: CreditCard },
  { id: 'ewallet', name: 'محافظ', nameEn: 'E-Wallets', icon: Wallet },
];

export default function FinancialServices() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const { region } = useRegion();
  const financial = dataByRegion[region];
  
  const [activeCategory, setActiveCategory] = useState('all');
  const [favorites, setFavorites] = useState<string[]>([]);
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(true);
  const [isDrawerOpen, setIsDrawerOpen] = useState(false);
  
  const scrollRef = useRef<HTMLDivElement>(null);

  const filteredFinancial = financial.filter(f => activeCategory === 'all' || f.category === activeCategory);

  const checkScroll = () => {
    if (scrollRef.current) {
      const { scrollLeft, scrollWidth, clientWidth } = scrollRef.current;
      setCanScrollLeft(scrollLeft > 0);
      setCanScrollRight(scrollLeft < scrollWidth - clientWidth - 10);
    }
  };

  useEffect(() => {
    checkScroll();
    const scrollEl = scrollRef.current;
    if (scrollEl) {
      scrollEl.addEventListener('scroll', checkScroll);
      return () => scrollEl.removeEventListener('scroll', checkScroll);
    }
  }, [filteredFinancial]);

  const scroll = (direction: 'left' | 'right') => {
    if (scrollRef.current) {
      scrollRef.current.scrollBy({ left: direction === 'left' ? -280 : 280, behavior: 'smooth' });
    }
  };

  const toggleFavorite = (id: string) => {
    setFavorites(prev => prev.includes(id) ? prev.filter(f => f !== id) : [...prev, id]);
  };

  return (
    <section className="py-2">
      <div className="max-w-7xl mx-auto">
        {/* Section Title */}
        <h2 className="text-xl font-bold text-gray-900 mb-4">
          {isArabic ? 'خدمات مالية' : 'Financial Services'}
        </h2>

        {/* Category Filters - Airbnb Style */}
        <div className="flex gap-6 mb-4 overflow-x-auto pb-2 scrollbar-hide">
          {categoryFilters.map((filter) => {
            const Icon = filter.icon;
            const isActive = activeCategory === filter.id;
            return (
              <button
                key={filter.id}
                onClick={() => setActiveCategory(filter.id)}
                className={`flex flex-col items-center gap-1.5 pb-2 min-w-[50px] transition-all border-b-2 ${
                  isActive ? 'border-gray-900 text-gray-900' : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-200'
                }`}
              >
                <Icon className="w-5 h-5" />
                <span className="text-xs font-medium">{isArabic ? filter.name : filter.nameEn}</span>
              </button>
            );
          })}
        </div>

        <div className="relative">
          {canScrollLeft && (
            <button onClick={() => scroll('left')} className="absolute left-0 top-1/2 -translate-y-1/2 z-10 w-8 h-8 bg-white rounded-full shadow-lg flex items-center justify-center border border-gray-200 hover:scale-105 transition-transform">
              <ChevronRight className="w-4 h-4 text-gray-700" />
            </button>
          )}
          {canScrollRight && (
            <button onClick={() => scroll('right')} className="absolute right-0 top-1/2 -translate-y-1/2 z-10 w-8 h-8 bg-white rounded-full shadow-lg flex items-center justify-center border border-gray-200 hover:scale-105 transition-transform">
              <ChevronLeft className="w-4 h-4 text-gray-700" />
            </button>
          )}

          <div ref={scrollRef} className="flex gap-4 overflow-x-auto pb-2 scroll-smooth scrollbar-hide" style={{ scrollbarWidth: 'none', msOverflowStyle: 'none' }}>
            {filteredFinancial.slice(0, 8).map((service) => {
              const isFavorite = favorites.includes(service.id);
              return (
                <div key={service.id} className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer">
                  <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                    <img src={service.image} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                    <button onClick={(e) => { e.stopPropagation(); toggleFavorite(service.id); }} className="absolute top-2 right-2 p-1.5 transition-transform hover:scale-110">
                      <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                    </button>
                    <span className="absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm bg-emerald-600/90 text-white">
                      {isArabic ? service.type : service.typeEn}
                    </span>
                    {service.featured && (
                      <span className="absolute bottom-2 left-2 px-2 py-0.5 bg-amber-500/90 text-white text-[10px] font-semibold rounded-full backdrop-blur-sm">
                        {isArabic ? 'مميز' : 'Featured'}
                      </span>
                    )}
                    {service.new && (
                      <span className="absolute bottom-2 right-2 px-2 py-0.5 bg-blue-500/90 text-white text-[10px] font-semibold rounded-full backdrop-blur-sm">
                        {isArabic ? 'جديد' : 'New'}
                      </span>
                    )}
                  </div>
                  <div>
                    <div className="flex items-center gap-0.5 mb-0.5">
                      <Star className="w-3 h-3 fill-gray-900 text-gray-900" />
                      <span className="text-xs font-medium">{service.rating}</span>
                      <span className="text-xs text-gray-400">({service.reviews})</span>
                    </div>
                    <h3 className="text-sm font-medium text-gray-900 mb-1 line-clamp-1">{isArabic ? service.name : service.nameEn}</h3>
                    <div className="flex flex-wrap gap-1 mb-1">
                      {(isArabic ? service.services : service.servicesEn).slice(0, 2).map((srv, i) => (
                        <span key={i} className="text-[9px] px-2 py-0.5 bg-emerald-100 text-emerald-700 rounded-full">{srv}</span>
                      ))}
                    </div>
                    <div className="flex items-center justify-between text-xs text-gray-500">
                      <span className="truncate flex-1">📍 {service.location}</span>
                      <a href={`tel:${service.phone}`} onClick={(e) => e.stopPropagation()} className="text-emerald-600 hover:underline flex-shrink-0">📞</a>
                    </div>
                  </div>
                </div>
              );
            })}
            
            {/* بطاقة عرض الكل */}
            {filteredFinancial.length > 8 && (
              <div 
                onClick={() => setIsDrawerOpen(true)}
                className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer"
              >
                <div className="relative aspect-square rounded-xl overflow-hidden mb-2 border-2 border-dashed border-gray-300 hover:border-gray-400 transition-colors">
                  <div className="grid grid-cols-2 gap-0.5 h-full">
                    {filteredFinancial.slice(0, 4).map((s, idx) => (
                      <div key={idx} className="relative overflow-hidden">
                        <img src={s.image} alt="" className="w-full h-full object-cover opacity-80" />
                      </div>
                    ))}
                  </div>
                  <div className="absolute inset-0 bg-black/40 flex flex-col items-center justify-center">
                    <span className="text-white text-lg font-bold">+{filteredFinancial.length - 8}</span>
                    <span className="text-white text-sm mt-1">{isArabic ? 'عرض الكل' : 'View All'}</span>
                  </div>
                </div>
                <div className="text-center">
                  <p className="text-sm text-gray-600 font-medium">{isArabic ? 'تصفح جميع الخدمات' : 'Browse all services'}</p>
                </div>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Drawer لعرض جميع الخدمات المالية */}
      <Drawer open={isDrawerOpen} onOpenChange={setIsDrawerOpen}>
        <DrawerContent className="max-h-[85vh]">
          <DrawerHeader className="border-b">
            <DrawerTitle className="flex items-center gap-2">
              💰 {isArabic ? 'جميع الخدمات المالية' : 'All Financial Services'}
            </DrawerTitle>
          </DrawerHeader>
          <div className="p-4 overflow-y-auto">
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-4">
              {filteredFinancial.map((service) => {
                const isFavorite = favorites.includes(service.id);
                return (
                  <div key={service.id} className="group cursor-pointer">
                    <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                      <img src={service.image} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                      <button onClick={(e) => { e.stopPropagation(); toggleFavorite(service.id); }} className="absolute top-2 right-2 p-1.5 transition-transform hover:scale-110">
                        <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                      </button>
                      <span className="absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm bg-emerald-600/90 text-white">
                        {isArabic ? service.type : service.typeEn}
                      </span>
                    </div>
                    <h3 className="text-sm font-medium text-gray-900 line-clamp-1">{isArabic ? service.name : service.nameEn}</h3>
                    <p className="text-xs text-gray-500 truncate">📍 {service.location}</p>
                  </div>
                );
              })}
            </div>
          </div>
        </DrawerContent>
      </Drawer>
    </section>
  );
}
