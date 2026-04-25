'use client';

import React, { useState, useRef, useEffect } from 'react';
import { Heart, Users, Gift, HandHeart, Phone, MapPin, Droplets, ChevronLeft, ChevronRight, AlertCircle, Star, Grid3X3 } from 'lucide-react';
import { useLanguage } from '@/contexts/LanguageContext';
import { useRegion, Region } from '@/contexts/RegionContext';
import { Drawer, DrawerContent, DrawerHeader, DrawerTitle } from '@/components/ui/drawer';

interface Charity {
  id: string;
  name: string;
  nameEn: string;
  type: string;
  typeEn: string;
  icon: React.ElementType;
  color: string;
  description: string;
  descriptionEn: string;
  phone: string;
  location: string;
  featured?: boolean;
  urgent?: boolean;
}

interface Campaign {
  id: string;
  title: string;
  titleEn: string;
  icon: React.ElementType;
  target: string;
  current: string;
  progress: number;
}

const qudsayaCenterCharities: Charity[] = [
  { id: '1', name: 'جمعية البر الخيرية', nameEn: 'Al-Birr Charity', type: 'إغاثة', typeEn: 'Relief', icon: HandHeart, color: 'bg-rose-600', description: 'مساعدة الأسر المحتاجة واليتامى في قدسيا', descriptionEn: 'Helping needy families and orphans in Qudsaya', phone: '011-1234567', location: 'قدسيا - الحي الرئيسي', featured: true },
  { id: '2', name: 'بنك الطعام قدسيا', nameEn: 'Qudsaya Food Bank', type: 'غذائي', typeEn: 'Food', icon: Gift, color: 'bg-orange-600', description: 'توزيع الطعام على المحتاجين', descriptionEn: 'Food distribution to the needy', phone: '011-2345678', location: 'قدسيا - الساحة', urgent: true },
  { id: '3', name: 'مؤسسة الأمل', nameEn: 'Al-Amal Foundation', type: 'صحي', typeEn: 'Health', icon: Heart, color: 'bg-red-600', description: 'علاج المرضى المحتاجين', descriptionEn: 'Treating needy patients', phone: '011-3456789', location: 'قدسيا' },
  { id: '4', name: 'جمعية التعليم الخيري', nameEn: 'Charity Education Association', type: 'تعليم', typeEn: 'Education', icon: Users, color: 'bg-blue-600', description: 'دعم تعليم الطلاب المحتاجين', descriptionEn: 'Supporting needy students education', phone: '011-4567890', location: 'قدسيا', featured: true },
  { id: '5', name: 'جمعية الإحسان', nameEn: 'Al-Ihsan Association', type: 'إغاثة', typeEn: 'Relief', icon: HandHeart, color: 'bg-pink-600', description: 'كفالة الأيتام والأرامل', descriptionEn: 'Sponsoring orphans and widows', phone: '011-5678901', location: 'قدسيا - الحي الشرقي' },
  { id: '6', name: 'مركز الرعاية الاجتماعية', nameEn: 'Social Care Center', type: 'صحي', typeEn: 'Health', icon: Heart, color: 'bg-purple-600', description: 'رعاية المسنين والمعاقين', descriptionEn: 'Caring for elderly and disabled', phone: '011-6789012', location: 'قدسيا - الحي الغربي', urgent: true }
];

const qudsayaDahiaCharities: Charity[] = [
  { id: '1', name: 'جمعية الخير الخيرية', nameEn: 'Al-Kheir Charity', type: 'إغاثة', typeEn: 'Relief', icon: HandHeart, color: 'bg-rose-600', description: 'مساعدة الأسر المحتاجة في الضاحية', descriptionEn: 'Helping needy families in Dahia', phone: '011-2234567', location: 'الضاحية - الحي الرئيسي', featured: true },
  { id: '2', name: 'بنك الطعام الضاحية', nameEn: 'Dahia Food Bank', type: 'غذائي', typeEn: 'Food', icon: Gift, color: 'bg-orange-600', description: 'توزيع الطعام على المحتاجين', descriptionEn: 'Food distribution to the needy', phone: '011-3345678', location: 'الضاحية - المركز', urgent: true },
  { id: '3', name: 'جمعية الشفاء', nameEn: 'Al-Shifa Association', type: 'صحي', typeEn: 'Health', icon: Heart, color: 'bg-red-600', description: 'رعاية صحية مجانية', descriptionEn: 'Free healthcare', phone: '011-4456789', location: 'الضاحية' },
  { id: '4', name: 'جمعية النور التعليمية', nameEn: 'Al-Noor Education', type: 'تعليم', typeEn: 'Education', icon: Users, color: 'bg-blue-600', description: 'دروس مجانية للطلاب', descriptionEn: 'Free tutoring for students', phone: '011-5567890', location: 'الضاحية', featured: true },
  { id: '5', name: 'جمعية البركة', nameEn: 'Al-Baraka Association', type: 'غذائي', typeEn: 'Food', icon: Gift, color: 'bg-amber-600', description: 'مشروع وليمة الحب', descriptionEn: 'Love Feast Project', phone: '011-6678901', location: 'الضاحية - الساحة' },
  { id: '6', name: 'مؤسسة الرحمة', nameEn: 'Al-Rahma Foundation', type: 'إغاثة', typeEn: 'Relief', icon: HandHeart, color: 'bg-teal-600', description: 'إغاثة الطوارئ للعائلات', descriptionEn: 'Emergency relief for families', phone: '011-7789012', location: 'الضاحية - الحي الجنوبي', urgent: true }
];

const qudsayaCenterCampaigns: Campaign[] = [
  { id: '1', title: 'سقيا الماء', titleEn: 'Water Wells', icon: Droplets, target: '50,000$', current: '35,000$', progress: 70 },
  { id: '2', title: 'كسوة العيد', titleEn: 'Eid Clothing', icon: Gift, target: '20,000$', current: '16,000$', progress: 80 }
];

const qudsayaDahiaCampaigns: Campaign[] = [
  { id: '1', title: 'إفطار صائم', titleEn: 'Ramadan Iftar', icon: Gift, target: '30,000$', current: '22,000$', progress: 73 },
  { id: '2', title: 'علاج المرضى', titleEn: 'Medical Treatment', icon: Heart, target: '40,000$', current: '28,000$', progress: 70 }
];

const dataByRegion: Record<Region, { charities: Charity[]; campaigns: Campaign[] }> = {
  'qudsaya-center': { charities: qudsayaCenterCharities, campaigns: qudsayaCenterCampaigns },
  'qudsaya-dahia': { charities: qudsayaDahiaCharities, campaigns: qudsayaDahiaCampaigns }
};

const typeFilters = [
  { id: 'all', name: 'الكل', nameEn: 'All', icon: HandHeart },
  { id: 'إغاثة', name: 'إغاثة', nameEn: 'Relief', icon: HandHeart },
  { id: 'غذائي', name: 'غذائي', nameEn: 'Food', icon: Gift },
  { id: 'صحي', name: 'صحي', nameEn: 'Health', icon: Heart },
  { id: 'تعليم', name: 'تعليم', nameEn: 'Education', icon: Users },
];

export default function Charity() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const { region } = useRegion();
  const { charities, campaigns } = dataByRegion[region];

  const [activeType, setActiveType] = useState('all');
  const [favorites, setFavorites] = useState<string[]>([]);
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(true);
  const [isDrawerOpen, setIsDrawerOpen] = useState(false);

  const scrollRef = useRef<HTMLDivElement>(null);

  const filteredCharities = charities.filter(charity => activeType === 'all' || charity.type === activeType);

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
  }, [filteredCharities]);

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
          {isArabic ? '❤️ خيري' : '❤️ Charity'}
        </h2>

        {/* Category Filters - Airbnb Style */}
        <div className="flex gap-6 mb-4 overflow-x-auto pb-2 scrollbar-hide">
          {typeFilters.map((filter) => {
            const Icon = filter.icon;
            const isActive = activeType === filter.id;
            return (
              <button
                key={filter.id}
                onClick={() => setActiveType(filter.id)}
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

        {/* Horizontal Scrolling Container - Airbnb Style */}
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
            {filteredCharities.slice(0, 8).map((charity) => {
              const Icon = charity.icon;
              const isFavorite = favorites.includes(charity.id);
              return (
                <div key={charity.id} className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer">
                  <div className="relative aspect-square rounded-xl overflow-hidden mb-2 bg-gradient-to-br from-gray-100 to-gray-200">
                    <div className={`absolute inset-0 ${charity.color} opacity-20`} />
                    <div className="absolute inset-0 flex items-center justify-center">
                      <div className={`w-20 h-20 ${charity.color} rounded-2xl flex items-center justify-center shadow-lg group-hover:scale-110 transition-transform`}>
                        <Icon className="w-10 h-10 text-white" />
                      </div>
                    </div>
                    <button onClick={(e) => { e.stopPropagation(); toggleFavorite(charity.id); }} className="absolute top-2 right-2 p-1.5 transition-transform hover:scale-110">
                      <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                    </button>
                    <span className={`absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm ${charity.color} text-white`}>
                      {isArabic ? charity.type : charity.typeEn}
                    </span>
                    {(charity.featured || charity.urgent) && (
                      <span className={`absolute bottom-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm ${
                        charity.featured ? 'bg-amber-500/90 text-white' : 'bg-red-500/90 text-white'
                      }`}>
                        {charity.featured ? (isArabic ? 'مميز' : 'Featured') : (isArabic ? 'عاجل' : 'Urgent')}
                      </span>
                    )}
                  </div>
                  <div>
                    <h3 className="text-sm font-medium text-gray-900 mb-1 line-clamp-1">{isArabic ? charity.name : charity.nameEn}</h3>
                    <p className="text-xs text-gray-500 mb-1 line-clamp-2">{isArabic ? charity.description : charity.descriptionEn}</p>
                    <div className="flex items-center justify-between text-xs text-gray-500">
                      <span className="truncate flex-1">📍 {charity.location}</span>
                      <a href={`tel:${charity.phone}`} onClick={(e) => e.stopPropagation()} className="text-rose-600 hover:underline flex-shrink-0">📞</a>
                    </div>
                  </div>
                </div>
              );
            })}
            
            {/* بطاقة عرض الكل */}
            {filteredCharities.length > 8 && (
              <div 
                onClick={() => setIsDrawerOpen(true)}
                className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer"
              >
                <div className="relative aspect-square rounded-xl overflow-hidden mb-2 border-2 border-dashed border-gray-300 hover:border-gray-400 transition-colors bg-gradient-to-br from-gray-50 to-gray-100 flex items-center justify-center">
                  <div className="text-center">
                    <Grid3X3 className="w-12 h-12 text-gray-400 mx-auto mb-2" />
                    <span className="text-lg font-bold text-gray-700">+{filteredCharities.length - 8}</span>
                    <p className="text-sm text-gray-500">{isArabic ? 'عرض الكل' : 'View All'}</p>
                  </div>
                </div>
                <div className="text-center">
                  <p className="text-sm text-gray-600 font-medium">{isArabic ? 'تصفح جميع الجمعيات' : 'Browse all charities'}</p>
                </div>
              </div>
            )}
          </div>
        </div>

        {/* Active Campaigns */}
        <div className="mt-4 bg-gradient-to-r from-rose-500 to-pink-600 rounded-xl p-4">
          <div className="flex items-center gap-2 mb-3">
            <Gift className="w-5 h-5 text-white" />
            <span className="text-white font-bold">{isArabic ? 'حملات نشطة' : 'Active Campaigns'}</span>
          </div>
          <div className="grid grid-cols-2 gap-3">
            {campaigns.map((campaign) => {
              const Icon = campaign.icon;
              return (
                <div key={campaign.id} className="bg-white/20 backdrop-blur rounded-lg p-3">
                  <div className="flex items-center gap-2 mb-2">
                    <div className="w-8 h-8 bg-white/30 rounded-lg flex items-center justify-center">
                      <Icon className="w-4 h-4 text-white" />
                    </div>
                    <span className="text-white font-medium text-sm">{isArabic ? campaign.title : campaign.titleEn}</span>
                  </div>
                  <div className="h-2 bg-white/30 rounded-full overflow-hidden mb-1">
                    <div className="h-full bg-white rounded-full" style={{ width: `${campaign.progress}%` }} />
                  </div>
                  <div className="flex justify-between text-xs text-white/80">
                    <span>{campaign.current}</span>
                    <span>{isArabic ? 'الهدف:' : 'Target:'} {campaign.target}</span>
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      </div>

      {/* Drawer لعرض جميع الجمعيات */}
      <Drawer open={isDrawerOpen} onOpenChange={setIsDrawerOpen}>
        <DrawerContent className="max-h-[85vh]">
          <DrawerHeader className="border-b">
            <DrawerTitle className="flex items-center gap-2">
              ❤️ {isArabic ? 'جميع الجمعيات الخيرية' : 'All Charities'}
            </DrawerTitle>
          </DrawerHeader>
          <div className="p-4 overflow-y-auto">
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-4">
              {filteredCharities.map((charity) => {
                const Icon = charity.icon;
                const isFavorite = favorites.includes(charity.id);
                return (
                  <div key={charity.id} className="group cursor-pointer">
                    <div className="relative aspect-square rounded-xl overflow-hidden mb-2 bg-gradient-to-br from-gray-100 to-gray-200">
                      <div className={`absolute inset-0 ${charity.color} opacity-20`} />
                      <div className="absolute inset-0 flex items-center justify-center">
                        <div className={`w-16 h-16 ${charity.color} rounded-2xl flex items-center justify-center shadow-lg`}>
                          <Icon className="w-8 h-8 text-white" />
                        </div>
                      </div>
                      <span className={`absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm ${charity.color} text-white`}>
                        {isArabic ? charity.type : charity.typeEn}
                      </span>
                    </div>
                    <h3 className="text-sm font-medium text-gray-900 line-clamp-1">{isArabic ? charity.name : charity.nameEn}</h3>
                    <p className="text-xs text-gray-500 truncate">📍 {charity.location}</p>
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
